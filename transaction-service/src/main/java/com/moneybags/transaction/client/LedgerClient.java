package com.moneybags.transaction.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@FeignClient(name = "ledger-service", url = "${moneybags.transaction.ledger-service-url:http://localhost:8085}")
public interface LedgerClient {
    @PostMapping("/api/v1/ledger/journals")
    JournalResponse post(@RequestBody JournalPostRequest request);

    record JournalPostRequest(String journalReference, String transactionId, String journalType,
                              String description, String currencyCode, String createdBy,
                              List<JournalLineRequest> lines) {}

    record JournalLineRequest(String ledgerCode, String customerAccountId, String side,
                              BigDecimal amount, String description) {}

    record JournalResponse(Long id, String journalReference, String transactionId, String journalType,
                           String description, String status, String currencyCode,
                           BigDecimal totalDebit, BigDecimal totalCredit, Long reversalOfJournalId,
                           Instant createdAt, Instant postedAt, String createdBy, List<Object> lines) {}
}
