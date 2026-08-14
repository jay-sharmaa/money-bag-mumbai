package com.moneybags.ledger.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public record JournalPostRequest(
        @Size(max = 100) String journalReference,
        @Size(max = 64) String transactionId,
        @Size(max = 40) String journalType,
        @Size(max = 500) String description,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currencyCode,
        @Size(max = 100) String createdBy,
        @NotNull @Size(min = 2) List<@Valid JournalLineRequest> lines
) {}
