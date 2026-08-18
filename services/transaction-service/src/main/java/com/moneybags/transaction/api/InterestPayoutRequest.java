package com.moneybags.transaction.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InterestPayoutRequest(
        @NotBlank String accountId,
        @NotNull @DecimalMin("1") BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Size(max = 36) String accrualId,
        @NotNull LocalDate periodStartDate,
        @NotNull LocalDate periodEndDate,
        @NotBlank String branchCode,
        String correlationId) {
}
