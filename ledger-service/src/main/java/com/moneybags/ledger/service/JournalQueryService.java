package com.moneybags.ledger.service;

import com.moneybags.ledger.dto.CustomerLedgerEntryResponse;
import com.moneybags.ledger.dto.JournalResponse;
import com.moneybags.ledger.entity.JournalEntry;
import com.moneybags.ledger.exception.InvalidJournalException;
import com.moneybags.ledger.exception.JournalNotFoundException;
import com.moneybags.ledger.mapper.LedgerMapper;
import com.moneybags.ledger.repository.JournalEntryRepository;
import com.moneybags.ledger.repository.JournalLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JournalQueryService {
    private final JournalEntryRepository journalRepository;
    private final JournalLineRepository lineRepository;
    private final LedgerMapper mapper;

    public JournalResponse findById(Long id) {
        return mapper.toJournalResponse(journalRepository.findDetailedById(id)
                .orElseThrow(() -> new JournalNotFoundException(id.toString())));
    }

    public JournalResponse findByReference(String reference) {
        return mapper.toJournalResponse(journalRepository.findByJournalReference(reference)
                .orElseThrow(() -> new JournalNotFoundException(reference)));
    }

    public List<JournalResponse> search(String transactionId, String customerAccountId) {
        if (transactionId != null && customerAccountId != null) {
            throw new InvalidJournalException("Use either transactionId or customerAccountId, not both");
        }
        List<JournalEntry> journals = transactionId != null
                ? journalRepository.findByTransactionIdOrderByCreatedAtDesc(transactionId)
                : customerAccountId != null
                    ? journalRepository.findByCustomerAccountId(customerAccountId)
                    : journalRepository.findAllByOrderByCreatedAtDesc();
        return journals.stream().map(mapper::toJournalResponse).toList();
    }

    public List<CustomerLedgerEntryResponse> customerEntries(String accountId) {
        return lineRepository.findByCustomerAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(mapper::toCustomerEntryResponse).toList();
    }
}
