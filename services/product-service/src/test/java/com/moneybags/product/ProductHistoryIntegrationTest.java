package com.moneybags.product;

import com.moneybags.product.dto.*;
import com.moneybags.product.entity.ProductType;
import com.moneybags.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProductHistoryIntegrationTest {

    @Autowired ProductService productService;
    @Autowired MockMvc mockMvc;

    @Test
    void allFourSelectedProductKindsStartWithAnImmutableVersionOne() {
        LocalDate start = LocalDate.now().minusYears(1);
        productService.create(request("SAV-REG", "Regular Savings", ProductType.SAVINGS,
                "3.5000", "1000", null, start));
        productService.create(request("SAV-SENIOR", "Senior Savings", ProductType.SAVINGS,
                "4.2500", "500", null, start));
        productService.create(request("CUR-BASIC", "Current Account", ProductType.CURRENT,
                "0", "5000", null, start));
        productService.create(request("FD-12M", "12-month Fixed Deposit", ProductType.TERM_DEPOSIT,
                "6.7500", "0", 12, start));

        for (String code : List.of("SAV-REG", "SAV-SENIOR", "CUR-BASIC", "FD-12M")) {
            List<ProductVersionDetail> history = productService.history(code);
            assertThat(history).singleElement().satisfies(version -> {
                assertThat(version.versionNumber()).isEqualTo(1);
                assertThat(version.productCode()).isEqualTo(code);
                assertThat(version.productVersionId()).isNotNull();
                assertThat(version.recordedAt()).isNotNull();
            });
        }
    }

    @Test
    void updatesChargesAndFutureTermsAppendVersionsWithoutChangingOldSnapshots() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate originalStart = today.minusYears(1);
        LocalDate futureStart = today.plusDays(30);

        productService.create(request("SAV-REG", "Regular Savings", ProductType.SAVINGS,
                "3.5000", "1000", null, originalStart));
        productService.replaceCharges("SAV-REG", List.of(
                new ChargeRequest("ATM_WITHDRAWAL", new BigDecimal("10.00"), "PER_TRANSACTION")));
        productService.update("SAV-REG", new UpdateProductRequest(
                "Regular Savings Plus", null, new BigDecimal("4.0000"), null,
                null, null, null, null, futureStart, null));

        List<ProductVersionDetail> history = productService.history("SAV-REG");
        assertThat(history).extracting(ProductVersionDetail::versionNumber)
                .containsExactly(3, 2, 1);

        ProductVersionDetail versionOne = productService.version("SAV-REG", 1);
        assertThat(versionOne.productName()).isEqualTo("Regular Savings");
        assertThat(versionOne.interestRate()).isEqualByComparingTo("3.5000");
        assertThat(versionOne.charges()).isEmpty();

        ProductVersionDetail versionTwo = productService.version("SAV-REG", 2);
        assertThat(versionTwo.productName()).isEqualTo("Regular Savings");
        assertThat(versionTwo.interestRate()).isEqualByComparingTo("3.5000");
        assertThat(versionTwo.charges()).singleElement().satisfies(charge ->
                assertThat(charge.amount()).isEqualByComparingTo("10.00"));

        ProductVersionDetail versionThree = productService.version("SAV-REG", 3);
        assertThat(versionThree.productName()).isEqualTo("Regular Savings Plus");
        assertThat(versionThree.interestRate()).isEqualByComparingTo("4.0000");

        assertThat(productService.asOf("SAV-REG", today.minusDays(1)).versionNumber()).isEqualTo(1);
        assertThat(productService.asOf("SAV-REG", today).versionNumber()).isEqualTo(2);
        assertThat(productService.asOf("SAV-REG", futureStart).versionNumber()).isEqualTo(3);
        assertThat(productService.effective("SAV-REG", today).versionNumber()).isEqualTo(2);
        assertThat(productService.effective("SAV-REG", futureStart).versionNumber()).isEqualTo(3);

        mockMvc.perform(get("/api/v1/products/SAV-REG/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].versionNumber").value(3))
                .andExpect(jsonPath("$[2].versionNumber").value(1));
        mockMvc.perform(get("/api/v1/products/SAV-REG/versions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("Regular Savings"));
        mockMvc.perform(get("/api/v1/products/SAV-REG/as-of").param("date", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNumber").value(2));

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['paths']['/api/v1/products/{productCode}/versions']").exists())
                .andExpect(jsonPath("$['paths']['/api/v1/products/{productCode}/versions/{versionNumber}']").exists())
                .andExpect(jsonPath("$['paths']['/api/v1/products/{productCode}/as-of']").exists())
                .andExpect(jsonPath("$['components']['schemas']['UpdateProductRequest']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.sessionId.type").value("apiKey"))
                .andExpect(jsonPath("$.components.securitySchemes.sessionId.in").value("header"))
                .andExpect(jsonPath("$.components.securitySchemes.sessionId.name").value("X-Session-Id"))
                .andExpect(jsonPath("$.security[0].sessionId").isArray());
    }

    private CreateProductRequest request(String code, String name, ProductType type,
                                         String rate, String minBalance, Integer tenure,
                                         LocalDate effectiveFrom) {
        return new CreateProductRequest(
                code, name, type, name + " description", "INR",
                new BigDecimal(rate), new BigDecimal(minBalance),
                type.isTermBased() ? new BigDecimal("5000") : new BigDecimal(minBalance),
                type.isTermBased() ? BigDecimal.ZERO : new BigDecimal("50000"),
                type.isTermBased() ? 0 : 5, tenure, type == ProductType.CURRENT,
                type.isTermBased(), 18, effectiveFrom);
    }
}
