package com.moneybags.account.repository;

import com.moneybags.account.entity.AccountProductOwnership;
import com.moneybags.account.entity.ProductAcquisitionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDate;
import java.util.Collection;
import com.moneybags.account.entity.ProductOwnershipStatus;
import com.moneybags.account.entity.FdSettlementStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountProductOwnershipRepository
        extends JpaRepository<AccountProductOwnership, String> {

    List<AccountProductOwnership> findByOwnerAccountIdOrderByAcquiredOnDescCreatedAtDesc(
            String ownerAccountId);

    Optional<AccountProductOwnership> findByOwnerAccountIdAndAcquisitionType(
            String ownerAccountId, ProductAcquisitionType acquisitionType);

    Optional<AccountProductOwnership> findByPurchaseTransactionId(String purchaseTransactionId);

    @Query("""
            SELECT ownership FROM AccountProductOwnership ownership
            WHERE ownership.productType = 'TERM_DEPOSIT'
              AND ownership.status = :status
              AND ownership.settlementStatus IN :settlementStatuses
              AND ownership.maturityDate <= :maturityDate
            ORDER BY ownership.maturityDate, ownership.ownershipId
            """)
    List<AccountProductOwnership> findDueTermDeposits(
            @Param("maturityDate") LocalDate maturityDate,
            @Param("status") ProductOwnershipStatus status,
            @Param("settlementStatuses") Collection<FdSettlementStatus> settlementStatuses);
}
