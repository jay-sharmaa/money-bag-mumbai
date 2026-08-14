package com.moneybags.ledger.dto;

import com.moneybags.ledger.enums.EntrySide;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record JournalLineRequest(
        @NotBlank @Size(max = 32) String ledgerCode,
        @Size(max = 64) String customerAccountId,
        @NotNull EntrySide side,
        @NotNull @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @Size(max = 500) String description
) {}
