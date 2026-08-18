package com.moneybags.account.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "interest_payout_batches", uniqueConstraints =
        @UniqueConstraint(name = "uk_interest_payout_account_period",
                columnNames = {"account_id", "period_end_date"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterestPayoutBatch {
    @Id
    @Column(name = "batch_id", length = 36)
    private String batchId;

    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    @Column(name = "period_start_date", nullable = false)
    private LocalDate periodStartDate;

    @Column(name = "period_end_date", nullable = false)
    private LocalDate periodEndDate;

    @Column(name = "weekly_accrual_count", nullable = false)
    private int weeklyAccrualCount;

    @Column(name = "accrued_amount", nullable = false, precision = 19, scale = 6)
    private BigDecimal accruedAmount;

    @Column(name = "payout_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal payoutAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InterestPayoutBatchStatus status;

    @Column(name = "payout_transaction_id", length = 36)
    private String payoutTransactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
