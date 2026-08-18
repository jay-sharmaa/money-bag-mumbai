package com.moneybags.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccountOpenApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void ownedProductsEndpointIsPublishedForSwaggerTesting() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("MoneyBags Account Service API"))
                .andExpect(jsonPath("$.paths['/api/v1/accounts/{accountId}/products']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/accounts/interest-runs']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/accounts/{accountId}/interest-accruals']").exists())
                .andExpect(jsonPath("$.components.schemas.OwnedProductView").exists());
    }
}
