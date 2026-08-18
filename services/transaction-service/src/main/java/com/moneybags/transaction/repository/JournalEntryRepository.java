package com.moneybags.transaction.repository;
import com.moneybags.transaction.entity.JournalEntry;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface JournalEntryRepository extends JpaRepository<JournalEntry,String>{
    @EntityGraph(attributePaths = "lines")
    List<JournalEntry> findByTransactionIdOrderByCreatedAt(String transactionId);
}
