package com.moneybags.account.repository;

import com.moneybags.account.entity.InterestRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface InterestRunRepository extends JpaRepository<InterestRun, String> {

    Optional<InterestRun> findByPeriodEndDate(LocalDate periodEndDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM InterestRun r WHERE r.periodEndDate = :periodEndDate")
    Optional<InterestRun> findByPeriodEndDateForUpdate(
            @Param("periodEndDate") LocalDate periodEndDate);

    List<InterestRun> findByScheduledAtLessThanEqualOrderByPeriodEndDateAsc(Instant scheduledAt);
}
