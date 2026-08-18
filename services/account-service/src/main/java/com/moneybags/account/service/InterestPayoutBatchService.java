package com.moneybags.account.service;

import com.moneybags.account.entity.Account;
import com.moneybags.account.entity.InterestAccrual;
import com.moneybags.account.entity.InterestPayoutBatch;
import com.moneybags.account.entity.InterestPayoutBatchStatus;
import com.moneybags.account.repository.InterestAccrualRepository;
import com.moneybags.account.repository.InterestPayoutBatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InterestPayoutBatchService {
    static final int WEEKS_PER_PAYOUT = 52;

    private final InterestAccrualRepository accruals;
    private final InterestPayoutBatchRepository batches;
    private final AccountEventPublisher events;

    public int createEligibleBatches(Account account, String correlationId) {
        int queued = 0;
        while (true) {
            List<InterestAccrual> unpaid = accruals
                    .findByAccountIdAndPostedFalseAndPayoutBatchIdIsNullOrderByAccrualDateAsc(
                            account.getAccountId());
            if (unpaid.size() < WEEKS_PER_PAYOUT) return queued;

            List<InterestAccrual> cycle = unpaid.subList(0, WEEKS_PER_PAYOUT);
            if (!isConsecutive(cycle)) return queued;

            BigDecimal accruedAmount = cycle.stream()
                    .map(InterestAccrual::getAccruedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(6, RoundingMode.HALF_EVEN);
            BigDecimal payoutAmount = accruedAmount.setScale(0, RoundingMode.HALF_UP);
            String batchId = UUID.randomUUID().toString();

            InterestPayoutBatch batch = InterestPayoutBatch.builder()
                    .batchId(batchId)
                    .accountId(account.getAccountId())
                    .periodStartDate(cycle.get(0).getAccrualDate().minusDays(6))
                    .periodEndDate(cycle.get(WEEKS_PER_PAYOUT - 1).getAccrualDate())
                    .weeklyAccrualCount(WEEKS_PER_PAYOUT)
                    .accruedAmount(accruedAmount)
                    .payoutAmount(payoutAmount)
                    .status(InterestPayoutBatchStatus.PENDING)
                    .createdAt(Instant.now())
                    .build();
            batches.save(batch);
            cycle.forEach(accrual -> accrual.setPayoutBatchId(batchId));
            accruals.saveAll(cycle);

            if (payoutAmount.signum() > 0) {
                events.enqueueInterestPayout(account, batch, correlationId);
                batch.setStatus(InterestPayoutBatchStatus.PAYOUT_QUEUED);
                queued++;
            } else {
                cycle.forEach(accrual -> accrual.setPosted(true));
                batch.setStatus(InterestPayoutBatchStatus.COMPLETED);
                batch.setCompletedAt(Instant.now());
            }
            batches.save(batch);
        }
    }

    private boolean isConsecutive(List<InterestAccrual> cycle) {
        for (int index = 1; index < cycle.size(); index++) {
            if (!cycle.get(index - 1).getAccrualDate().plusWeeks(1)
                    .equals(cycle.get(index).getAccrualDate())) {
                return false;
            }
        }
        return true;
    }
}
