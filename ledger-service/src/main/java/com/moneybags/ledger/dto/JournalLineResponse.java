package com.moneybags.ledger.dto;

import com.moneybags.ledger.enums.EntrySide;

import java.math.BigDecimal;
import java.time.Instant;

public record JournalLineResponse(
        Long id,
        int lineNumber,
        String ledgerCode,
        String ledgerAccountName,
        String customerAccountId,
        EntrySide side,
        BigDecimal amount,
        String description,
        Instant createdAt
) {}
