package com.moneybags.transaction.entity;

import com.moneybags.transaction.domain.ProductPurchaseStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "product_purchases")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductPurchase {

    @Id
    @Column(name = "purchase_id", length = 36)
    private String purchaseId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, unique = true)
    private Transaction transaction;

    @Column(name = "owner_account_id", nullable = false, length = 64)
    private String ownerAccountId;

    @Column(name = "product_code", nullable = false, length = 30)
    private String productCode;

    @Column(name = "product_name", nullable = false, length = 120)
    private String productName;

    @Column(name = "product_type", nullable = false, length = 30)
    private String productType;

    @Column(name = "product_version_id")
    private Long productVersionId;

    @Column(name = "product_version_number")
    private Integer productVersionNumber;

    @Column(name = "principal_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal principalAmount;

    @Column(nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String currency;

    @Column(name = "interest_rate", nullable = false, precision = 8, scale = 4)
    private BigDecimal interestRate;

    @Column(name = "tenure_months", nullable = false)
    private Integer tenureMonths;

    @Column(name = "purchased_on", nullable = false)
    private LocalDate purchasedOn;

    @Column(name = "maturity_date", nullable = false)
    private LocalDate maturityDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductPurchaseStatus status;

    @Column(name = "reversal_transaction_id", unique = true, length = 36)
    private String reversalTransactionId;

    @Column(name = "settlement_type", length = 24)
    private String settlementType;

    @Column(name = "settlement_transaction_id", unique = true, length = 36)
    private String settlementTransactionId;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void create() {
        if (purchaseId == null) purchaseId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = ProductPurchaseStatus.PENDING;
    }

    @PreUpdate
    void update() {
        updatedAt = Instant.now();
    }
}
