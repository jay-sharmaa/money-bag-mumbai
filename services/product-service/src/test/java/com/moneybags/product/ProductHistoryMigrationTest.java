package com.moneybags.product;

import com.moneybags.product.dto.EffectiveProduct;
import com.moneybags.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:productmigration;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class ProductHistoryMigrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ProductService productService;

    @Test
    void migrationPreservesOriginalTermsAndPublishesWeeklySavingsVersions() {
        Integer currentProducts = jdbc.queryForObject("SELECT COUNT(*) FROM products", Integer.class);
        Integer versionedProducts = jdbc.queryForObject("SELECT COUNT(*) FROM product_versions", Integer.class);
        Integer distinctVersionedProducts = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT product_code) FROM product_versions", Integer.class);
        BigDecimal currentRate = jdbc.queryForObject(
                "SELECT interest_rate FROM products WHERE product_code = 'SAV-REG'", BigDecimal.class);
        BigDecimal historicalRate = jdbc.queryForObject(
                "SELECT interest_rate FROM product_versions WHERE product_code = 'SAV-REG' AND version_number = 1",
                BigDecimal.class);

        assertThat(currentProducts).isEqualTo(6);
        assertThat(versionedProducts).isEqualTo(6);
        assertThat(distinctVersionedProducts).isEqualTo(4);
        assertThat(historicalRate).isEqualByComparingTo(currentRate);
        assertThat(jdbc.queryForObject(
                "SELECT rule_value FROM product_version_rules pvr "
                        + "JOIN product_versions pv ON pv.product_version_id = pvr.product_version_id "
                        + "WHERE pv.product_code = 'SAV-REG' AND pv.version_number = 1 "
                        + "AND pvr.rule_key = 'INTEREST_PAYOUT'", String.class)).isEqualTo("QUARTERLY");
        assertThat(jdbc.queryForObject(
                "SELECT rule_value FROM product_version_rules pvr "
                        + "JOIN product_versions pv ON pv.product_version_id = pvr.product_version_id "
                        + "WHERE pv.product_code = 'SAV-REG' AND pv.version_number = 2 "
                        + "AND pvr.rule_key = 'INTEREST_PAYOUT'", String.class)).isEqualTo("WEEKLY");
        assertThat(jdbc.queryForObject(
                "SELECT rule_value FROM product_rules WHERE product_code = 'SAV-REG' "
                        + "AND rule_key = 'INTEREST_PAYOUT'", String.class)).isEqualTo("WEEKLY");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_version_charges", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_version_rules", Integer.class)).isPositive();

        EffectiveProduct effective = productService.effective("SAV-REG", LocalDate.of(2026, 8, 16));
        assertThat(effective.versionNumber()).isEqualTo(1);
        assertThat(effective.productVersionId()).isNotNull();
        assertThat(effective.interestRate()).isEqualByComparingTo(currentRate);

        EffectiveProduct weekly = productService.effective("SAV-REG", LocalDate.of(2026, 8, 17));
        assertThat(weekly.versionNumber()).isEqualTo(2);
    }
}
