package com.moneybags.account.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "account_product_ownerships")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountProductOwnership {

    @Id
    @Column(name = "ownership_id", length = 64)
    private String ownershipId;

    @Column(name = "owner_account_id", nullable = false, length = 36)
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

    @Enumerated(EnumType.STRING)
    @Column(name = "acquisition_type", nullable = false, length = 30)
    private ProductAcquisitionType acquisitionType;

    @Column(name = "principal_amount", precision = 19, scale = 4)
    private BigDecimal principalAmount;

    @Column(nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String currency;

    @Column(name = "interest_rate", nullable = false, precision = 8, scale = 4)
    private BigDecimal interestRate;

    @Column(name = "tenure_months")
    private Integer tenureMonths;

    @Column(name = "acquired_on", nullable = false)
    private LocalDate acquiredOn;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductOwnershipStatus status;

    @Column(name = "purchase_transaction_id", unique = true, length = 36)
    private String purchaseTransactionId;

    @Column(name = "reversal_transaction_id", unique = true, length = 36)
    private String reversalTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_status", nullable = false, length = 20)
    private FdSettlementStatus settlementStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_type", length = 24)
    private FdSettlementType settlementType;

    @Column(name = "settlement_destination_account_id", length = 36)
    private String settlementDestinationAccountId;

    @Column(name = "settlement_interest_amount", precision = 19, scale = 4)
    private BigDecimal settlementInterestAmount;

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
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (settlementStatus == null) settlementStatus = FdSettlementStatus.NONE;
    }

    @PreUpdate
    void update() {
        updatedAt = Instant.now();
    }
}
