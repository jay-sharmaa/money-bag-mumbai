package com.moneybags.account.service;

import com.moneybags.account.config.AccountProperties;
import com.moneybags.account.dto.InterestRunView;
import com.moneybags.account.entity.InterestRun;
import com.moneybags.account.entity.InterestRunStatus;
import com.moneybags.account.repository.InterestRunRepository;
import com.moneybags.account.support.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InterestRunStateService {
    private final InterestRunRepository runs;
    private final AccountProperties properties;

    @Transactional
    public InterestRun ensureScheduled(LocalDate periodEndDate, Instant scheduledAt) {
        return runs.findByPeriodEndDate(periodEndDate).orElseGet(() ->
                runs.saveAndFlush(InterestRun.builder()
                        .periodStartDate(periodEndDate.minusDays(6))
                        .periodEndDate(periodEndDate)
                        .scheduledAt(scheduledAt)
                        .status(InterestRunStatus.SCHEDULED)
                        .attempts(0)
                        .build()));
    }

    @Transactional
    public Claim claim(LocalDate periodEndDate, Instant scheduledAt) {
        ensureScheduled(periodEndDate, scheduledAt);
        InterestRun run = runs.findByPeriodEndDateForUpdate(periodEndDate).orElseThrow();
        if (run.getStatus() == InterestRunStatus.COMPLETED) {
            return new Claim(run, false);
        }
        Instant now = Instant.now();
        if (run.getStatus() == InterestRunStatus.RUNNING
                && run.getStartedAt() != null
                && run.getStartedAt().isAfter(staleBefore(now))) {
            throw ApiException.conflict("INTEREST_RUN_IN_PROGRESS",
                    "Interest calculation is already running for period ending " + periodEndDate);
        }
        run.setStatus(InterestRunStatus.RUNNING);
        run.setAttempts(run.getAttempts() + 1);
        run.setStartedAt(now);
        run.setCompletedAt(null);
        run.setNextAttemptAt(null);
        run.setLastError(null);
        return new Claim(runs.save(run), true);
    }

    @Transactional
    public void complete(String runId, InterestRunView result) {
        InterestRun run = runs.findById(runId).orElseThrow();
        run.setStatus(InterestRunStatus.COMPLETED);
        run.setAccountsEvaluated(result.accountsEvaluated());
        run.setAccrualsCreated(result.accrualsCreated());
        run.setPayoutsQueued(result.payoutsQueued());
        run.setAccountsSkipped(result.accountsSkipped());
        run.setCompletedAt(Instant.now());
        run.setNextAttemptAt(null);
        run.setLastError(null);
        runs.save(run);
    }

    @Transactional
    public void fail(String runId, RuntimeException failure) {
        InterestRun run = runs.findById(runId).orElse(null);
        if (run == null) return;
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        run.setStatus(InterestRunStatus.FAILED);
        run.setLastError(message.substring(0, Math.min(500, message.length())));
        run.setNextAttemptAt(Instant.now().plus(
                properties.getInterest().getRetryDelayMinutes(), ChronoUnit.MINUTES));
        runs.save(run);
    }

    @Transactional
    public void ensureWeeklySchedule(LocalDate firstPeriodEnd, LocalDate throughPeriodEnd,
                                     SavingsInterestSchedule schedule) {
        for (LocalDate end = firstPeriodEnd; !end.isAfter(throughPeriodEnd); end = end.plusWeeks(1)) {
            ensureScheduled(end, schedule.scheduledAt(end));
        }
    }

    @Transactional(readOnly = true)
    public List<InterestRun> due(Instant now) {
        Instant staleBefore = staleBefore(now);
        return runs.findByScheduledAtLessThanEqualOrderByPeriodEndDateAsc(now).stream()
                .filter(run -> run.getStatus() == InterestRunStatus.SCHEDULED
                        || run.getStatus() == InterestRunStatus.FAILED
                        && (run.getNextAttemptAt() == null || !run.getNextAttemptAt().isAfter(now))
                        || run.getStatus() == InterestRunStatus.RUNNING
                        && run.getStartedAt() != null && !run.getStartedAt().isAfter(staleBefore))
                .limit(properties.getInterest().getMaxCatchUpRuns())
                .toList();
    }

    private Instant staleBefore(Instant now) {
        return now.minus(properties.getInterest().getStaleAfterMinutes(), ChronoUnit.MINUTES);
    }

    public record Claim(InterestRun run, boolean execute) {
    }
}
