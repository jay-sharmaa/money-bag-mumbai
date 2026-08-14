package com.moneybags.ledger.service;

import com.moneybags.ledger.dto.*;
import com.moneybags.ledger.entity.*;
import com.moneybags.ledger.enums.*;
import com.moneybags.ledger.exception.*;
import com.moneybags.ledger.mapper.LedgerMapper;
import com.moneybags.ledger.repository.*;
import com.moneybags.ledger.service.account.AccountLookupPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class JournalPostingService {
    private static final BigDecimal ZERO = new BigDecimal("0.0000");

    private final JournalEntryRepository journalRepository;
    private final LedgerAccountRepository accountRepository;
    private final AccountLookupPort accountLookupPort;
    private final LedgerMapper mapper;
    private final TransactionTemplate transactionTemplate;

    public JournalPostingService(JournalEntryRepository journalRepository,
                                 LedgerAccountRepository accountRepository,
                                 AccountLookupPort accountLookupPort,
                                 LedgerMapper mapper,
                                 PlatformTransactionManager transactionManager) {
        this.journalRepository = journalRepository;
        this.accountRepository = accountRepository;
        this.accountLookupPort = accountLookupPort;
        this.mapper = mapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public JournalResponse post(JournalPostRequest request) {
        NormalizedJournal normalized = normalize(request);
        try {
            return transactionTemplate.execute(status -> {
                Optional<JournalEntry> existing = journalRepository.findByJournalReference(normalized.reference());
                if (existing.isPresent()) {
                    if (!matches(existing.get(), normalized)) {
                        throw new DuplicateJournalException(normalized.reference());
                    }
                    return mapper.toJournalResponse(existing.get());
                }
                return mapper.toJournalResponse(postNew(normalized, null, true));
            });
        } catch (DataIntegrityViolationException exception) {
            Optional<JournalEntry> concurrentWinner = journalRepository
                    .findByJournalReference(normalized.reference());
            if (concurrentWinner.isPresent()) {
                if (!matches(concurrentWinner.get(), normalized)) {
                    throw new DuplicateJournalException(normalized.reference());
                }
                return mapper.toJournalResponse(concurrentWinner.get());
            }
            throw exception;
        }
    }

    @Transactional
    public JournalResponse reverse(Long journalId, ReversalRequest request) {
        JournalEntry original = journalRepository.lockById(journalId)
                .orElseThrow(() -> new JournalNotFoundException(journalId.toString()));
        if (original.getStatus() == JournalStatus.REVERSED
                || journalRepository.findByReversalOfJournalId(journalId).isPresent()) {
            throw new JournalAlreadyReversedException(journalId);
        }
        if (original.getStatus() != JournalStatus.POSTED) {
            throw new InvalidJournalException("Only a POSTED journal can be reversed");
        }

        JournalEntry detailed = journalRepository.findDetailedById(journalId)
                .orElseThrow(() -> new JournalNotFoundException(journalId.toString()));
        ReversalRequest safeRequest = request == null ? new ReversalRequest(null, null, null) : request;
        String reference = hasText(safeRequest.journalReference())
                ? safeRequest.journalReference().trim()
                : defaultReversalReference(detailed.getJournalReference());
        if (journalRepository.findByJournalReference(reference).isPresent()) {
            throw new DuplicateJournalException(reference);
        }

        List<NormalizedLine> reversedLines = detailed.immutableLines().stream()
                .map(line -> new NormalizedLine(line.getLedgerCode(), line.getCustomerAccountId(),
                        line.getSide().opposite(), line.getAmount(), line.getDescription()))
                .toList();
        NormalizedJournal reversal = new NormalizedJournal(reference, detailed.getTransactionId(), "REVERSAL",
                hasText(safeRequest.description()) ? safeRequest.description().trim()
                        : "Reversal of " + detailed.getJournalReference(),
                detailed.getCurrencyCode(), hasText(safeRequest.createdBy())
                        ? safeRequest.createdBy().trim() : "SYSTEM", reversedLines);

        JournalEntry reversalEntry = postNew(reversal, journalId, false);
        original.markReversed();
        journalRepository.save(original);
        return mapper.toJournalResponse(reversalEntry);
    }

    private JournalEntry postNew(NormalizedJournal journal, Long reversalOfJournalId,
                                 boolean validateCustomerAccounts) {
        if (journal.lines().size() < 2) {
            throw new InvalidJournalException("A journal must contain at least two lines");
        }

        Set<String> codes = journal.lines().stream().map(NormalizedLine::ledgerCode)
                .collect(Collectors.toCollection(TreeSet::new));
        Map<String, LedgerAccount> accounts = accountRepository.lockAllByCodeIn(codes).stream()
                .collect(Collectors.toMap(LedgerAccount::getCode, Function.identity()));
        for (String code : codes) {
            if (!accounts.containsKey(code)) {
                throw new LedgerAccountNotFoundException(code);
            }
        }

        BigDecimal debit = ZERO;
        BigDecimal credit = ZERO;
        for (NormalizedLine line : journal.lines()) {
            if (line.amount() == null || line.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidJournalException("Journal amounts must be greater than zero");
            }
            if (line.side() == null) {
                throw new InvalidJournalException("Every journal line must have exactly one side");
            }
            LedgerAccount account = accounts.get(line.ledgerCode());
            validateAccount(account, journal.currencyCode(), line.customerAccountId(), validateCustomerAccounts);
            if (line.side() == EntrySide.DEBIT) debit = debit.add(line.amount());
            else credit = credit.add(line.amount());
        }
        if (debit.compareTo(credit) != 0) {
            throw new UnbalancedJournalException(debit, credit);
        }

        JournalEntry entry = JournalEntry.posted(journal.reference(), journal.transactionId(), journal.journalType(),
                journal.description(), journal.currencyCode(), debit, credit, reversalOfJournalId, journal.createdBy());
        int lineNumber = 1;
        for (NormalizedLine line : journal.lines()) {
            LedgerAccount account = accounts.get(line.ledgerCode());
            account.apply(line.side(), line.amount());
            entry.addLine(JournalLine.of(entry, lineNumber++, account, line.customerAccountId(),
                    line.side(), line.amount(), line.description()));
        }
        accountRepository.saveAll(accounts.values());
        return journalRepository.saveAndFlush(entry);
    }

    private void validateAccount(LedgerAccount account, String journalCurrency, String customerAccountId,
                                 boolean validateCustomerAccount) {
        if (!account.isActive()) {
            throw new InvalidJournalException("Ledger account is inactive: " + account.getCode());
        }
        if (!account.getCurrencyCode().equalsIgnoreCase(journalCurrency)) {
            throw new CurrencyMismatchException("Ledger account " + account.getCode() + " uses "
                    + account.getCurrencyCode() + " but journal uses " + journalCurrency);
        }
        if (customerAccountId != null && validateCustomerAccount) {
            AccountLookupPort.AccountSummary customerAccount = accountLookupPort.findByAccountId(customerAccountId);
            if (!"ACTIVE".equalsIgnoreCase(customerAccount.status())) {
                throw new InvalidJournalException("Customer account is not active: " + customerAccountId);
            }
            if (!journalCurrency.equalsIgnoreCase(customerAccount.currencyCode())) {
                throw new CurrencyMismatchException("Customer account " + customerAccountId + " uses "
                        + customerAccount.currencyCode() + " but journal uses " + journalCurrency);
            }
        }
    }

    private NormalizedJournal normalize(JournalPostRequest request) {
        if (request == null) throw new InvalidJournalException("Journal request is required");
        String reference = hasText(request.journalReference())
                ? request.journalReference().trim() : "JE-" + UUID.randomUUID();
        String currency = hasText(request.currencyCode()) ? request.currencyCode().trim().toUpperCase(Locale.ROOT) : null;
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new InvalidJournalException("A three-letter currencyCode is required");
        }
        if (request.lines() == null || request.lines().size() < 2) {
            throw new InvalidJournalException("A journal must contain at least two lines");
        }
        List<NormalizedLine> lines = request.lines().stream().map(line -> {
            if (line == null || !hasText(line.ledgerCode())) {
                throw new InvalidJournalException("Every line requires a ledgerCode");
            }
            return new NormalizedLine(line.ledgerCode().trim(), line.customerAccountId(), line.side(),
                    line.amount(), trimToNull(line.description()));
        }).toList();
        return new NormalizedJournal(reference, request.transactionId(),
                hasText(request.journalType()) ? request.journalType().trim().toUpperCase(Locale.ROOT) : "GENERAL",
                trimToNull(request.description()), currency,
                hasText(request.createdBy()) ? request.createdBy().trim() : "SYSTEM", lines);
    }

    private boolean matches(JournalEntry existing, NormalizedJournal request) {
        if (!Objects.equals(existing.getTransactionId(), request.transactionId())
                || !existing.getCurrencyCode().equals(request.currencyCode())
                || existing.immutableLines().size() != request.lines().size()) return false;
        for (int index = 0; index < request.lines().size(); index++) {
            JournalLine saved = existing.immutableLines().get(index);
            NormalizedLine supplied = request.lines().get(index);
            if (!saved.getLedgerCode().equals(supplied.ledgerCode())
                    || !Objects.equals(saved.getCustomerAccountId(), supplied.customerAccountId())
                    || saved.getSide() != supplied.side()
                    || supplied.amount() == null
                    || saved.getAmount().compareTo(supplied.amount()) != 0) return false;
        }
        return true;
    }

    private String defaultReversalReference(String originalReference) {
        return "REV-" + originalReference.substring(0, Math.min(originalReference.length(), 96));
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record NormalizedJournal(String reference, String transactionId, String journalType,
                                     String description, String currencyCode, String createdBy,
                                     List<NormalizedLine> lines) {}
    private record NormalizedLine(String ledgerCode, String customerAccountId, EntrySide side,
                                  BigDecimal amount, String description) {}
}
