package com.moneybags.ledger.service.account;

public interface AccountLookupPort {
    AccountSummary findByAccountId(String accountId);

    record AccountSummary(String accountId, String customerId, String currencyCode, String status) {}
}
