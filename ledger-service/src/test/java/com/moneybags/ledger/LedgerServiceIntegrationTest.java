package com.moneybags.ledger;

import com.moneybags.ledger.dto.*;
import com.moneybags.ledger.enums.*;
import com.moneybags.ledger.exception.*;
import com.moneybags.ledger.repository.*;
import com.moneybags.ledger.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class LedgerServiceIntegrationTest {
    @Autowired JournalPostingService postingService;
    @Autowired JournalQueryService queryService;
    @Autowired LedgerAccountService accountService;
    @Autowired LedgerAccountRepository accountRepository;
    @Autowired JournalEntryRepository journalRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetLedger() {
        jdbc.update("DELETE FROM ledger_journal_lines");
        jdbc.update("DELETE FROM ledger_journal_entries");
        jdbc.update("UPDATE ledger_accounts SET balance = 0, version = 0, active = 1");
    }

    @Test
    void loadsConfiguredSeedAccountsWithCorrectNormalSides() {
        assertThat(accountRepository.findAllByOrderByCodeAsc())
                .extracting(account -> account.getCode())
                .containsExactly("110100", "210000", "220100", "220200", "410100");
        assertThat(accountRepository.findByCode("110100").orElseThrow().getNormalSide()).isEqualTo(EntrySide.DEBIT);
        assertThat(accountRepository.findByCode("210000").orElseThrow().getNormalSide()).isEqualTo(EntrySide.CREDIT);
        assertThat(accountRepository.findByCode("220100").orElseThrow().getNormalSide()).isEqualTo(EntrySide.CREDIT);
        assertThat(accountRepository.findByCode("220200").orElseThrow().getNormalSide()).isEqualTo(EntrySide.CREDIT);
        assertThat(accountRepository.findByCode("410100").orElseThrow().getNormalSide()).isEqualTo(EntrySide.CREDIT);
    }

    @Test
    void postsDepositAndUpdatesDebitAndCreditNormalBalances() {
        JournalResponse journal = postingService.post(deposit("JE-501-DEPOSIT"));

        assertThat(journal.status()).isEqualTo(JournalStatus.POSTED);
        assertThat(journal.totalDebit()).isEqualByComparingTo("500.00");
        assertThat(journal.totalCredit()).isEqualByComparingTo("500.00");
        assertBalance("110100", "500.00");
        assertBalance("210000", "500.00");
        assertThat(queryService.customerEntries("10001")).hasSize(1);
    }

    @Test
    void postsWithdrawalUsingNormalSideRules() {
        JournalResponse journal = postingService.post(new JournalPostRequest(
                "JE-502-WITHDRAWAL", "TX-502", "WITHDRAWAL", "Cash withdrawal", "INR", "transaction-service",
                List.of(
                        line("210000", "10001", EntrySide.DEBIT, "41.00"),
                        line("110100", null, EntrySide.CREDIT, "40.00"),
                        line("410100", null, EntrySide.CREDIT, "1.00")
                )));

        assertThat(journal.totalDebit()).isEqualByComparingTo("41.00");
        assertBalance("210000", "-41.00");
        assertBalance("110100", "-40.00");
        assertBalance("410100", "1.00");
    }

    @Test
    void internalClearingReturnsToItsOriginalBalanceAfterSettlement() {
        postingService.post(new JournalPostRequest("JE-503-PAYER", "TX-503", "TRANSFER_PAYER", null, "INR", null,
                List.of(
                        line("210000", "10001", EntrySide.DEBIT, "252.00"),
                        line("220100", null, EntrySide.CREDIT, "250.00"),
                        line("410100", null, EntrySide.CREDIT, "2.00")
                )));
        assertBalance("220100", "250.00");

        postingService.post(new JournalPostRequest("JE-503-CLEAR", "TX-503", "TRANSFER_SETTLEMENT", null, "INR", null,
                List.of(
                        line("220100", null, EntrySide.DEBIT, "250.00"),
                        line("210000", "20001", EntrySide.CREDIT, "250.00")
                )));

        assertBalance("220100", "0.00");
        assertBalance("210000", "-2.00");
        assertBalance("410100", "2.00");
        assertThat(queryService.search("TX-503", null)).hasSize(2);
    }

    @Test
    void rejectsUnbalancedJournalAtomically() {
        JournalPostRequest request = new JournalPostRequest("JE-BAD", "TX-900", null, null, "INR", null,
                List.of(
                        line("210000", "10001", EntrySide.DEBIT, "100.00"),
                        line("220100", null, EntrySide.CREDIT, "90.00")
                ));

        assertThatThrownBy(() -> postingService.post(request)).isInstanceOf(UnbalancedJournalException.class);
        assertThat(journalRepository.count()).isZero();
        assertBalance("210000", "0.00");
        assertBalance("220100", "0.00");
    }

    @Test
    void identicalReferenceIsIdempotentButDifferentPayloadConflicts() {
        JournalPostRequest request = deposit("JE-IDEMPOTENT");

        JournalResponse first = postingService.post(request);
        JournalResponse retry = postingService.post(request);

        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(journalRepository.count()).isEqualTo(1);
        assertBalance("110100", "500.00");
        JournalPostRequest changed = new JournalPostRequest("JE-IDEMPOTENT", "TX-501", "DEPOSIT", null, "INR", null,
                List.of(line("110100", null, EntrySide.DEBIT, "600.00"),
                        line("210000", "10001", EntrySide.CREDIT, "600.00")));
        assertThatThrownBy(() -> postingService.post(changed)).isInstanceOf(DuplicateJournalException.class);
    }

    @Test
    void reversalPreservesOriginalLinesAndNetsGlBalancesToZero() {
        JournalResponse original = postingService.post(deposit("JE-TO-REVERSE"));

        JournalResponse reversal = postingService.reverse(original.id(),
                new ReversalRequest("REV-JE-TO-REVERSE", "Correct mistaken deposit", "ops-user"));

        JournalResponse storedOriginal = queryService.findById(original.id());
        assertThat(storedOriginal.status()).isEqualTo(JournalStatus.REVERSED);
        assertThat(storedOriginal.lines()).extracting(JournalLineResponse::side)
                .containsExactly(EntrySide.DEBIT, EntrySide.CREDIT);
        assertThat(reversal.reversalOfJournalId()).isEqualTo(original.id());
        assertThat(reversal.lines()).extracting(JournalLineResponse::side)
                .containsExactly(EntrySide.CREDIT, EntrySide.DEBIT);
        assertBalance("110100", "0.00");
        assertBalance("210000", "0.00");
        assertThatThrownBy(() -> postingService.reverse(original.id(), null))
                .isInstanceOf(JournalAlreadyReversedException.class);
    }

    @Test
    void rejectsUnknownCodesCurrenciesAccountsAndInvalidAmounts() {
        assertThatThrownBy(() -> postingService.post(new JournalPostRequest("JE-UNKNOWN", null, null, null, "INR", null,
                List.of(line("999999", null, EntrySide.DEBIT, "1.00"),
                        line("110100", null, EntrySide.CREDIT, "1.00")))))
                .isInstanceOf(LedgerAccountNotFoundException.class);

        assertThatThrownBy(() -> postingService.post(new JournalPostRequest("JE-EUR", null, null, null, "EUR", null,
                List.of(line("110100", null, EntrySide.DEBIT, "1.00"),
                        line("210000", null, EntrySide.CREDIT, "1.00")))))
                .isInstanceOf(CurrencyMismatchException.class);

        assertThatThrownBy(() -> postingService.post(new JournalPostRequest("JE-NO-ACCOUNT", null, null, null, "INR", null,
                List.of(line("110100", null, EntrySide.DEBIT, "1.00"),
                        line("210000", "99999", EntrySide.CREDIT, "1.00")))))
                .isInstanceOf(AccountLookupException.class);

        assertThatThrownBy(() -> postingService.post(new JournalPostRequest("JE-ZERO", null, null, null, "INR", null,
                List.of(line("110100", null, EntrySide.DEBIT, "0.00"),
                        line("210000", null, EntrySide.CREDIT, "0.00")))))
                .isInstanceOf(InvalidJournalException.class);

        jdbc.update("UPDATE ledger_accounts SET active = 0 WHERE code = '220200'");
        assertThatThrownBy(() -> postingService.post(new JournalPostRequest("JE-INACTIVE", null, null, null, "INR", null,
                List.of(line("110100", null, EntrySide.DEBIT, "1.00"),
                        line("220200", null, EntrySide.CREDIT, "1.00")))))
                .isInstanceOf(InvalidJournalException.class)
                .hasMessageContaining("inactive");
        assertThat(journalRepository.count()).isZero();
    }

    private JournalPostRequest deposit(String reference) {
        return new JournalPostRequest(reference, "TX-501", "DEPOSIT", "Customer cash deposit", "INR", "transaction-service",
                List.of(
                        line("110100", null, EntrySide.DEBIT, "500.00"),
                        line("210000", "10001", EntrySide.CREDIT, "500.00")
                ));
    }

    private JournalLineRequest line(String code, String accountId, EntrySide side, String amount) {
        return new JournalLineRequest(code, accountId, side, new BigDecimal(amount), null);
    }

    private void assertBalance(String code, String expected) {
        assertThat(accountService.balance(code).balance()).isEqualByComparingTo(expected);
    }
}
