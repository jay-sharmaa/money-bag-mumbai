package com.moneybags.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record InterestAccrualView(
        String accrualId,
        String accountId,
        String productCode,
        LocalDate periodStartDate,
        LocalDate periodEndDate,
        BigDecimal sevenDayAverageBalance,
        BigDecimal annualInterestRate,
        Integer dayCountBasis,
        BigDecimal interestAmount,
        boolean posted,
        String postedTransactionId,
        Instant createdAt) {
}
