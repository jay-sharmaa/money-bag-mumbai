package com.moneybags.account.repository;

import com.moneybags.account.entity.InterestPayoutBatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterestPayoutBatchRepository extends JpaRepository<InterestPayoutBatch, String> {
}
