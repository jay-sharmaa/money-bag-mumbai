package com.moneybags.transaction.api;

import com.moneybags.transaction.domain.FdSettlementType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FdSettlementRequest(
        @NotBlank @Size(max = 64) String ownershipId,
        @Size(max = 36) String purchaseTransactionId,
        @Size(max = 36) String sourceFdAccountId,
        @NotBlank String destinationAccountId,
        @NotNull @DecimalMin("0.0001") BigDecimal principalAmount,
        @NotNull @DecimalMin("0.0000") BigDecimal interestAmount,
        @NotNull @DecimalMin("0.0000") BigDecimal interestRate,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull FdSettlementType settlementType,
        @NotNull LocalDate acquiredOn,
        @NotNull LocalDate maturityDate,
        @NotNull LocalDate settlementDate,
        @NotBlank String branchCode,
        String correlationId) {
}
