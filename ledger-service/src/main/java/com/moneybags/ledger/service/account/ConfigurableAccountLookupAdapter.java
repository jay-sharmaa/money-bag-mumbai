package com.moneybags.ledger.service.account;

import com.moneybags.ledger.config.LedgerProperties;
import com.moneybags.ledger.exception.AccountLookupException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConfigurableAccountLookupAdapter implements AccountLookupPort {
    private final LedgerProperties properties;

    @Override
    public AccountSummary findByAccountId(String accountId) {
        if (!properties.fakeAccounts().enabled()) {
            throw new AccountLookupException("Account Service integration is not configured");
        }
        LedgerProperties.FakeAccount account = properties.fakeAccounts().accounts().get(accountId);
        if (account == null) {
            throw new AccountLookupException("Customer account not found: " + accountId);
        }
        return new AccountSummary(accountId, account.customerId(), account.currencyCode(), account.status());
    }
}
