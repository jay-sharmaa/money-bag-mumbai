package com.moneybags.ledger;

import com.moneybags.ledger.repository.JournalEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class LedgerApiIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JournalEntryRepository journals;

    @BeforeEach
    void resetLedger() {
        jdbc.update("DELETE FROM ledger_journal_lines");
        jdbc.update("DELETE FROM ledger_journal_entries");
        jdbc.update("UPDATE ledger_accounts SET balance = 0, version = 0, active = 1");
    }

    @Test
    void postsAndQueriesAJournalThroughHttp() throws Exception {
        String body = """
                {
                  "journalReference":"API-DEPOSIT-1",
                  "transactionId":"TX-701",
                  "currencyCode":"INR",
                  "lines":[
                    {"ledgerCode":"110100","side":"DEBIT","amount":500.00},
                    {"ledgerCode":"210000","customerAccountId":"10001","side":"CREDIT","amount":500.00}
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/ledger/journals").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journalReference").value("API-DEPOSIT-1"))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.totalDebit").value(500.0));

        mockMvc.perform(get("/api/v1/ledger/journals/reference/API-DEPOSIT-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2));
        mockMvc.perform(get("/api/v1/ledger/customer-accounts/10001/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ledgerCode").value("210000"));
        mockMvc.perform(get("/api/v1/ledger/accounts/110100/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(500.0));
        assertThat(journals.count()).isEqualTo(1);
    }

    @Test
    void exposesOpenApiAndMeaningfulValidationErrors() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/ledger/journals']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ledger/journals/{journalId}/reverse']").exists());

        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));

        mockMvc.perform(post("/api/v1/ledger/journals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"journalReference\":\"BAD\",\"currencyCode\":\"USD\",\"lines\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
    }
}
