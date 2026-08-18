package com.moneybags.account.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.math.BigDecimal;
import java.time.LocalDate;

@FeignClient(name = "transaction-service")
public interface TransactionClient {

    @PostMapping("/internal/v1/transactions/opening-deposits")
    void createOpeningDeposit(@RequestHeader("X-Service-Name") String serviceName,
                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                              @RequestBody OpeningDepositCommand command);

    @PostMapping("/internal/v1/transactions/interest-payouts")
    InterestPayoutResult createInterestPayout(@RequestHeader("X-Service-Name") String serviceName,
                                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @RequestBody InterestPayoutCommand command);

    @PostMapping("/internal/v1/transactions/fd-settlements")
    FdSettlementResult createFdSettlement(@RequestHeader("X-Service-Name") String serviceName,
                                          @RequestHeader("Idempotency-Key") String idempotencyKey,
                                          @RequestBody FdSettlementCommand command);

    record OpeningDepositCommand(
            String accountId,
            BigDecimal amount,
            String currency,
            String applicationReference,
            String initiatedByEmployeeId,
            String branchCode,
            String correlationId) {
    }

    record InterestPayoutCommand(
            String accountId,
            BigDecimal amount,
            String currency,
            String payoutBatchId,
            LocalDate periodStartDate,
            LocalDate periodEndDate,
            String branchCode,
            String correlationId) {
    }

    record InterestPayoutResult(String id, String reference, String status) {
    }

    record FdSettlementCommand(
            String ownershipId,
            String purchaseTransactionId,
            String sourceFdAccountId,
            String destinationAccountId,
            BigDecimal principalAmount,
            BigDecimal interestAmount,
            BigDecimal interestRate,
            String currency,
            String settlementType,
            LocalDate acquiredOn,
            LocalDate maturityDate,
            LocalDate settlementDate,
            String branchCode,
            String correlationId) {
    }

    record FdSettlementResult(String id, String reference, String status) {
    }
}
