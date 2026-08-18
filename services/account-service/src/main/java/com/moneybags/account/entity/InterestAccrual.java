package com.moneybags.account.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "interest_accruals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterestAccrual {

    @Id
    @Column(name = "accrual_id", length = 36)
    private String accrualId;

    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    @Column(name = "accrual_date", nullable = false)
    private LocalDate accrualDate;

    @Column(name = "principal_base", nullable = false, precision = 19, scale = 4)
    private BigDecimal principalBase;

    @Column(nullable = false, precision = 8, scale = 4)
    private BigDecimal rate;

    @Column(name = "day_count_basis", nullable = false)
    private Integer dayCountBasis;

    @Column(name = "accrued_amount", nullable = false, precision = 19, scale = 6)
    private BigDecimal accruedAmount;

    @Column(nullable = false)
    private Boolean posted;

    @Column(name = "posted_transaction_id", length = 36)
    private String postedTransactionId;

    @Column(name = "payout_batch_id", length = 36)
    private String payoutBatchId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
