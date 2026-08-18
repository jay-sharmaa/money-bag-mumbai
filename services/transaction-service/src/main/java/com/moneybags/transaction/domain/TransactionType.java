package com.moneybags.transaction.domain;

public enum TransactionType {
    DEPOSIT, WITHDRAWAL, INTERNAL_TRANSFER, NEFT, RTGS, IMPS, UPI, CHEQUE,
    CARD_PAYMENT, PRODUCT_PURCHASE, INTEREST_PAYOUT,
    FD_MATURITY_PAYOUT, FD_PREMATURE_BREAK, REVERSAL;

    public boolean debitsAccount() {
        return this != DEPOSIT && this != CHEQUE && this != INTEREST_PAYOUT
                && this != FD_MATURITY_PAYOUT && this != FD_PREMATURE_BREAK;
    }

    public boolean externallyCleared() {
        return this == NEFT || this == RTGS || this == IMPS || this == UPI || this == CHEQUE || this == CARD_PAYMENT;
    }
}
