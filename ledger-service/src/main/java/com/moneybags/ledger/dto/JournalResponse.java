package com.moneybags.ledger.dto;

import com.moneybags.ledger.enums.JournalStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record JournalResponse(
        Long id,
        String journalReference,
        String transactionId,
        String journalType,
        String description,
        JournalStatus status,
        String currencyCode,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        Long reversalOfJournalId,
        Instant createdAt,
        Instant postedAt,
        String createdBy,
        List<JournalLineResponse> lines
) {}
