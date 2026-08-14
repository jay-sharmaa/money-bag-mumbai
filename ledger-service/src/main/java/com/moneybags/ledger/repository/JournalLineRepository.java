package com.moneybags.ledger.repository;

import com.moneybags.ledger.entity.JournalLine;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JournalLineRepository extends JpaRepository<JournalLine, Long> {
    @EntityGraph(attributePaths = {"journalEntry", "ledgerAccount"})
    List<JournalLine> findByCustomerAccountIdOrderByCreatedAtDesc(String customerAccountId);
}
