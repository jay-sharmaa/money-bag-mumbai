package com.moneybags.transaction.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.math.BigDecimal;
import java.time.Instant;

@FeignClient(name = "statement-reporting-service", url = "${moneybags.transaction.statement-service-url:http://localhost:8086}")
public interface StatementClient {
    @PostMapping("/internal/v1/statement-read-model/transactions")
    IngestResult project(@RequestHeader("X-Service-Name") String serviceName,
                         @RequestBody TransactionEvent event);

    record TransactionEvent(String sourceEventId, String transactionId, String ledgerEntryId,
                            String transactionReference, String accountId, String customerId,
                            String branchId, String direction, BigDecimal amount, BigDecimal feeAmount,
                            String currency, String transactionType, String status, String narration,
                            String reversalOfTransactionId, Instant postedAt, BigDecimal balanceAfter,
                            Instant sourceUpdatedAt) {}

    record IngestResult(String sourceEventId, String result) {}
}
