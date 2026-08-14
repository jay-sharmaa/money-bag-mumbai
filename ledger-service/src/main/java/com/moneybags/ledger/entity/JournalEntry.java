package com.moneybags.ledger.entity;

import com.moneybags.ledger.enums.JournalStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "ledger_journal_entries")
@Getter
@NoArgsConstructor
public class JournalEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "journal_reference", nullable = false, unique = true, length = 100)
    private String journalReference;

    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "journal_type", nullable = false, length = 40)
    private String journalType;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JournalStatus status;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "total_debit", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalDebit;

    @Column(name = "total_credit", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalCredit;

    @Column(name = "reversal_of_journal_id", unique = true)
    private Long reversalOfJournalId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Version
    @Column(nullable = false)
    private long version;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL, orphanRemoval = false)
    @OrderBy("lineNumber ASC")
    @Getter(AccessLevel.NONE)
    private List<JournalLine> lines = new ArrayList<>();

    public static JournalEntry posted(String reference, String transactionId, String journalType,
                                      String description, String currencyCode, BigDecimal totalDebit,
                                      BigDecimal totalCredit, Long reversalOfJournalId, String createdBy) {
        JournalEntry entry = new JournalEntry();
        entry.journalReference = reference;
        entry.transactionId = transactionId;
        entry.journalType = journalType;
        entry.description = description;
        entry.status = JournalStatus.POSTED;
        entry.currencyCode = currencyCode;
        entry.totalDebit = totalDebit;
        entry.totalCredit = totalCredit;
        entry.reversalOfJournalId = reversalOfJournalId;
        entry.createdBy = createdBy;
        entry.createdAt = Instant.now();
        entry.postedAt = entry.createdAt;
        return entry;
    }

    public void addLine(JournalLine line) {
        if (status != JournalStatus.POSTED || id != null) {
            throw new IllegalStateException("Posted journal lines are immutable");
        }
        lines.add(line);
    }

    public void markReversed() {
        if (status != JournalStatus.POSTED) {
            throw new IllegalStateException("Only posted journals can be reversed");
        }
        status = JournalStatus.REVERSED;
    }

    public List<JournalLine> immutableLines() {
        return Collections.unmodifiableList(lines);
    }
}
