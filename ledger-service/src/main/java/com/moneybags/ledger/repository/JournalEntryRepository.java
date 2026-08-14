package com.moneybags.ledger.repository;

import com.moneybags.ledger.entity.JournalEntry;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {
    @EntityGraph(attributePaths = {"lines", "lines.ledgerAccount"})
    @Query("select j from JournalEntry j where j.id = :id")
    Optional<JournalEntry> findDetailedById(@Param("id") Long id);

    @EntityGraph(attributePaths = {"lines", "lines.ledgerAccount"})
    Optional<JournalEntry> findByJournalReference(String journalReference);

    Optional<JournalEntry> findByReversalOfJournalId(Long reversalOfJournalId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from JournalEntry j where j.id = :id")
    Optional<JournalEntry> lockById(@Param("id") Long id);

    @EntityGraph(attributePaths = {"lines", "lines.ledgerAccount"})
    List<JournalEntry> findByTransactionIdOrderByCreatedAtDesc(String transactionId);

    @EntityGraph(attributePaths = {"lines", "lines.ledgerAccount"})
    List<JournalEntry> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"lines", "lines.ledgerAccount"})
    @Query("select distinct j from JournalEntry j join j.lines l where l.customerAccountId = :accountId order by j.createdAt desc")
    List<JournalEntry> findByCustomerAccountId(@Param("accountId") String accountId);
}
