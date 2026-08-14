package com.moneybags.ledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "moneybags.ledger")
public record LedgerProperties(
        String defaultCurrency,
        Accounts accounts,
        FakeAccounts fakeAccounts
) {
    public LedgerProperties {
        defaultCurrency = defaultCurrency == null ? "INR" : defaultCurrency.toUpperCase();
        accounts = accounts == null ? new Accounts("110100", "210000", "220100", "220200", "410100") : accounts;
        fakeAccounts = fakeAccounts == null ? new FakeAccounts(true, new LinkedHashMap<>()) : fakeAccounts;
    }

    public record Accounts(
            String cashAsset,
            String accountDepositControl,
            String internalClearing,
            String externalClearing,
            String feeIncome
    ) {}

    public record FakeAccounts(boolean enabled, Map<String, FakeAccount> accounts) {
        public FakeAccounts {
            accounts = accounts == null ? new LinkedHashMap<>() : accounts;
        }
    }

    public record FakeAccount(String customerId, String currencyCode, String status) {}
}
