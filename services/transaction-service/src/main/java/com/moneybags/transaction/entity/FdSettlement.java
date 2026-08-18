package com.moneybags.transaction.entity;

import com.moneybags.transaction.domain.FdSettlementType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "fd_settlements", uniqueConstraints = {
        @UniqueConstraint(name = "uk_fd_settlement_transaction", columnNames = "transaction_id"),
        @UniqueConstraint(name = "uk_fd_settlement_ownership", columnNames = "ownership_id")})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FdSettlement {
    @Id
    @Column(name = "settlement_id", length = 36)
    private String settlementId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, unique = true)
    private Transaction transaction;

    @Column(name = "ownership_id", nullable = false, unique = true, length = 64)
    private String ownershipId;

    @Column(name = "purchase_transaction_id", length = 36)
    private String purchaseTransactionId;

    @Column(name = "source_fd_account_id", length = 36)
    private String sourceFdAccountId;

    @Column(name = "destination_account_id", nullable = false, length = 36)
    private String destinationAccountId;

    @Column(name = "principal_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal principalAmount;

    @Column(name = "interest_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal interestAmount;

    @Column(name = "interest_rate", nullable = false, precision = 8, scale = 4)
    private BigDecimal interestRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_type", nullable = false, length = 24)
    private FdSettlementType settlementType;

    @Column(name = "acquired_on", nullable = false)
    private LocalDate acquiredOn;

    @Column(name = "maturity_date", nullable = false)
    private LocalDate maturityDate;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void create() {
        if (settlementId == null) settlementId = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }
}
