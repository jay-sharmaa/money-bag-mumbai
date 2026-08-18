package com.moneybags.transaction.service;

import com.moneybags.transaction.client.LedgerClient;
import com.moneybags.transaction.domain.TransactionType;
import com.moneybags.transaction.entity.JournalEntry;
import com.moneybags.transaction.entity.JournalLine;
import com.moneybags.transaction.entity.Transaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class LedgerPostingCatalog {
    private static final Map<String, String> STATIC_LEDGER_NAMES = Map.of(
            "110100", "Cash and Settlement Asset",
            "210000", "Customer Deposit Control",
            "210100", "Term Deposit Control",
            "220100", "Internal Payment Clearing",
            "220200", "External Clearing",
            "410100", "Payment Fee Income",
            "510100", "Savings Interest Expense");

    public LedgerClient.JournalPostRequest request(Transaction transaction, JournalEntry journal) {
        String type = postingType(transaction, journal);
        return new LedgerClient.JournalPostRequest(
                journal.getReference(), transaction.getId(), type, description(type),
                transaction.getCurrency(), "transaction-service",
                journal.getLines().stream().map(this::line).toList());
    }

    private LedgerClient.JournalLineRequest line(JournalLine line) {
        boolean debit = line.getDebit().compareTo(BigDecimal.ZERO) > 0;
        return new LedgerClient.JournalLineRequest(
                line.getLedgerAccountCode(), line.getAccountId(), debit ? "DEBIT" : "CREDIT",
                debit ? line.getDebit() : line.getCredit(),
                STATIC_LEDGER_NAMES.getOrDefault(line.getLedgerAccountCode(), "General Ledger Account"));
    }

    private String postingType(Transaction transaction, JournalEntry journal) {
        if (transaction.getType() == TransactionType.REVERSAL || journal.getType().startsWith("REVERSAL")) {
            return "REVERSAL";
        }
        if ("SETTLEMENT".equals(journal.getType()) || "CHEQUE_SETTLEMENT".equals(journal.getType())) {
            return "SETTLEMENT";
        }
        if (transaction.getType() == TransactionType.DEPOSIT) return "DEPOSIT";
        if (transaction.getType() == TransactionType.WITHDRAWAL) return "WITHDRAWAL";
        if (transaction.getType() == TransactionType.PRODUCT_PURCHASE) return "PRODUCT_PURCHASE";
        if (transaction.getType() == TransactionType.INTEREST_PAYOUT) return "INTEREST_PAYOUT";
        if (transaction.getType() == TransactionType.FD_MATURITY_PAYOUT) return "FD_MATURITY_PAYOUT";
        if (transaction.getType() == TransactionType.FD_PREMATURE_BREAK) return "FD_PREMATURE_BREAK";
        return "PAYMENT";
    }

    private String description(String postingType) {
        return switch (postingType) {
            case "DEPOSIT" -> "Customer deposit journal";
            case "WITHDRAWAL" -> "Customer withdrawal journal";
            case "PRODUCT_PURCHASE" -> "Term deposit purchase journal";
            case "INTEREST_PAYOUT" -> "Savings interest payout journal";
            case "FD_MATURITY_PAYOUT" -> "Fixed deposit maturity journal";
            case "FD_PREMATURE_BREAK" -> "Premature fixed deposit break journal";
            case "PAYMENT" -> "Customer payment journal";
            case "SETTLEMENT" -> "Payment settlement journal";
            case "REVERSAL" -> "Transaction reversal journal";
            default -> "General journal";
        };
    }
}
