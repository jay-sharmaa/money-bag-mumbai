package com.moneybags.account.repository;

import com.moneybags.account.entity.InterestAccrual;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface InterestAccrualRepository extends JpaRepository<InterestAccrual, String> {
    Optional<InterestAccrual> findByAccountIdAndAccrualDate(String accountId, LocalDate accrualDate);

    List<InterestAccrual> findByAccountIdOrderByAccrualDateDesc(String accountId);

    List<InterestAccrual> findByAccrualDateOrderByAccountIdAsc(LocalDate accrualDate);

    boolean existsByAccountIdAndAccrualDateBetween(String accountId, LocalDate from, LocalDate to);

    List<InterestAccrual> findByAccountIdAndPostedFalseAndPayoutBatchIdIsNullOrderByAccrualDateAsc(
            String accountId);

    List<InterestAccrual> findByPayoutBatchIdOrderByAccrualDateAsc(String payoutBatchId);
}
