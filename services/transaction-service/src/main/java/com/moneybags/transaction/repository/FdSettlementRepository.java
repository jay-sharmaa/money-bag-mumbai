package com.moneybags.transaction.repository;

import com.moneybags.transaction.entity.FdSettlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FdSettlementRepository extends JpaRepository<FdSettlement, String> {
    Optional<FdSettlement> findByTransaction_Id(String transactionId);
    Optional<FdSettlement> findByOwnershipId(String ownershipId);
}
