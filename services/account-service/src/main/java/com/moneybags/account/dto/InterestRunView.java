package com.moneybags.account.dto;

import java.time.LocalDate;
import java.util.List;

public record InterestRunView(
        LocalDate periodStartDate,
        LocalDate periodEndDate,
        int accountsEvaluated,
        int accrualsCreated,
        int payoutsQueued,
        int accountsSkipped,
        List<InterestAccrualView> accruals) {
}
