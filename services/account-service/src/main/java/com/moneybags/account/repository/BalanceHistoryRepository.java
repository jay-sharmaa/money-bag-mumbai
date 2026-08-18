package com.moneybags.account.repository;

import com.moneybags.account.entity.BalanceHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BalanceHistoryRepository extends JpaRepository<BalanceHistory, Long> {
    Page<BalanceHistory> findByAccountIdOrderByCreatedAtDesc(String accountId, Pageable pageable);

    List<BalanceHistory> findByAccountIdOrderByBusinessDateAscCreatedAtAsc(String accountId);
}
