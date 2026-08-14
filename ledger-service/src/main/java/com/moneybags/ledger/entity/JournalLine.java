package com.moneybags.ledger.entity;

import com.moneybags.ledger.enums.EntrySide;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "ledger_journal_lines")
@Getter
@NoArgsConstructor
public class JournalLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    private JournalEntry journalEntry;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ledger_account_id", nullable = false)
    private LedgerAccount ledgerAccount;

    @Column(name = "ledger_code", nullable = false, length = 32)
    private String ledgerCode;

    @Column(name = "customer_account_id", length = 64)
    private String customerAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private EntrySide side;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(length = 500)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static JournalLine of(JournalEntry journal, int lineNumber, LedgerAccount account,
                                 String customerAccountId, EntrySide side, BigDecimal amount,
                                 String description) {
        JournalLine line = new JournalLine();
        line.journalEntry = journal;
        line.lineNumber = lineNumber;
        line.ledgerAccount = account;
        line.ledgerCode = account.getCode();
        line.customerAccountId = customerAccountId;
        line.side = side;
        line.amount = amount;
        line.description = description;
        line.createdAt = Instant.now();
        return line;
    }
}
