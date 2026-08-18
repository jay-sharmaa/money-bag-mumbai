package com.moneybags.statement;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.time.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class StatementReportingIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired StatementService statements; @Autowired GeneratedFileRepository files;
    private final String user="user-1",account="account-1";

    @BeforeEach void project() throws Exception {
        mvc.perform(post("/internal/v1/statement-read-model/accounts").header("X-Service-Name","account-service").contentType(MediaType.APPLICATION_JSON).content("""
          {"sourceEventId":"acct-event-1","accountId":"account-1","customerId":"cif-1","branchId":"branch-1","maskedAccountNumber":"XXXX1234","accountName":"Primary Savings","status":"ACTIVE","currency":"INR","currentBalance":1250.00,"sourceUpdatedAt":"2026-08-01T10:00:00Z"}
          """)).andExpect(status().isOk());
        mvc.perform(post("/internal/v1/statement-read-model/transactions").header("X-Service-Name","transaction-service").contentType(MediaType.APPLICATION_JSON).content("""
          {"sourceEventId":"tx-event-1","transactionId":"tx-1","ledgerEntryId":"ledger-1","transactionReference":"MB-1","accountId":"account-1","customerId":"cif-1","branchId":"branch-1","direction":"CREDIT","amount":500.00,"feeAmount":0,"currency":"INR","transactionType":"DEPOSIT","status":"SUCCESS","narration":"Opening deposit","postedAt":"2026-08-01T10:00:00Z","balanceAfter":1250.00,"sourceUpdatedAt":"2026-08-01T10:00:01Z"}
          """)).andExpect(status().isOk());
    }

    @Test void miniAndGeneratedCsvAreEndToEnd() throws Exception {
        mvc.perform(get("/api/v1/statements/accounts/{id}/mini",account).headers(auth("STATEMENT_VIEW"))).andExpect(status().isOk()).andExpect(jsonPath("$.entries[0].ledgerEntryId").value("ledger-1"));
        String body=mvc.perform(post("/api/v1/statements/accounts/{id}",account).headers(auth("STATEMENT_VIEW")).header("Idempotency-Key","csv-1").contentType(MediaType.APPLICATION_JSON).content("""
          {"fromDate":"2026-08-01","toDate":"2026-08-31","outputFormat":"CSV","statementKind":"MONTHLY"}
          """)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String id=json.readTree(body).get("requestId").asText();statements.generateNow(id);
        var file=files.findByRequestId(id).orElseThrow();assertThat(file.checksumSha256).hasSize(64);assertThat(file.fileSizeBytes).isPositive();
    }

    @Test void idempotencyConflictAndScopeAreEnforced() throws Exception {
        var request=post("/api/v1/statements/accounts/{id}",account).headers(auth("STATEMENT_VIEW")).header("Idempotency-Key","same-key").contentType(MediaType.APPLICATION_JSON);
        mvc.perform(request.content("{\"fromDate\":\"2026-08-01\",\"toDate\":\"2026-08-02\",\"outputFormat\":\"PDF\"}")).andExpect(status().isAccepted());
        mvc.perform(request.content("{\"fromDate\":\"2026-08-01\",\"toDate\":\"2026-08-03\",\"outputFormat\":\"PDF\"}")).andExpect(status().isConflict());
        var denied=auth("STATEMENT_VIEW");denied.set("X-Customer-Id","other-cif");
        mvc.perform(get("/api/v1/statements/accounts/{id}/mini",account).headers(denied)).andExpect(status().isForbidden());
    }

    @Test void savingsInterestAppearsInStatementsAndInterestReports() throws Exception {
        mvc.perform(post("/internal/v1/statement-read-model/transactions")
                .header("X-Service-Name", "transaction-service")
                .contentType(MediaType.APPLICATION_JSON).content("""
                  {"sourceEventId":"interest-event-1","transactionId":"interest-tx-1","ledgerEntryId":"interest-ledger-1","transactionReference":"TXN-INT-1","accountId":"account-1","customerId":"cif-1","branchId":"branch-1","direction":"CREDIT","amount":3.00,"feeAmount":0,"currency":"INR","transactionType":"INTEREST_PAYOUT","status":"SUCCESS","narration":"Savings interest 2026-07-27 to 2026-08-02","postedAt":"2026-08-02T00:10:00Z","balanceAfter":1253.00,"sourceUpdatedAt":"2026-08-02T00:10:01Z"}
                  """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/statements/accounts/{id}/mini", account)
                        .headers(auth("STATEMENT_VIEW")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].type").value("INTEREST_PAYOUT"))
                .andExpect(jsonPath("$.entries[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[0].amount.amount").value("3.00"));

        mvc.perform(get("/api/v1/reports/interest-accruals")
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .headers(auth("REPORT_VIEW")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountId").value(account))
                .andExpect(jsonPath("$[0].accrued.amount").value("3.00"));
    }

    private org.springframework.http.HttpHeaders auth(String permission){var h=new org.springframework.http.HttpHeaders();h.set("X-User-Id",user);h.set("X-Customer-Id","cif-1");h.set("X-Permissions",permission);h.set("X-Correlation-Id","test-correlation");return h;}
}
