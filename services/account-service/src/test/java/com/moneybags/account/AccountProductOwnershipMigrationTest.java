package com.moneybags.account;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class AccountProductOwnershipMigrationTest {

    @Test
    void additiveMigrationBackfillsFocusedProductsWithoutChangingAccounts() throws Exception {
        String url = "jdbc:h2:mem:account_ownership_migration;MODE=MySQL;"
                + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load().migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            try (var accounts = statement.executeQuery("SELECT COUNT(*) FROM accounts")) {
                accounts.next();
                assertThat(accounts.getInt(1)).isEqualTo(3);
            }
            try (var ownerships = statement.executeQuery(
                    "SELECT COUNT(*) FROM account_product_ownerships")) {
                ownerships.next();
                assertThat(ownerships.getInt(1)).isEqualTo(3);
            }
            try (var interestRuns = statement.executeQuery(
                    "SELECT COUNT(*) FROM interest_runs")) {
                interestRuns.next();
                assertThat(interestRuns.getInt(1)).isZero();
            }
            try (var rows = statement.executeQuery("""
                    SELECT owner_account_id, product_code, interest_rate, status,
                           product_version_number
                      FROM account_product_ownerships
                     ORDER BY owner_account_id
                    """)) {
                rows.next();
                assertThat(rows.getString("product_code")).isEqualTo("SAV-REG");
                assertThat(rows.getBigDecimal("interest_rate")).isEqualByComparingTo("3.5");
                assertThat(rows.getString("status")).isEqualTo("ACTIVE");
                assertThat(rows.getObject("product_version_number")).isNull();
            }
        }
    }
}
