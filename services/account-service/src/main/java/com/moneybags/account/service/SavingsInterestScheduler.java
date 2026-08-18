package com.moneybags.account.service;

import com.moneybags.account.config.AccountProperties;
import com.moneybags.account.entity.InterestRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;

@Component
@RequiredArgsConstructor
@Slf4j
public class SavingsInterestScheduler {
    private final InterestCalculationService interest;
    private final InterestRunStateService runStates;
    private final SavingsInterestSchedule schedule;
    private final AccountProperties properties;

    @Scheduled(
            fixedDelayString = "${moneybags.account.interest.check-delay-ms:60000}",
            initialDelayString = "${moneybags.account.interest.initial-delay-ms:10000}")
    public void recoverDueRuns() {
        if (!properties.getInterest().isEnabled()) return;

        Instant now = Instant.now();
        LocalDate latestDue = schedule.latestDuePeriodEnd(now);
        LocalDate first = properties.getInterest().getFirstPeriodEnd();
        LocalDate scheduleThrough = latestDue.plusWeeks(1);
        if (scheduleThrough.isBefore(first)) scheduleThrough = first;
        runStates.ensureWeeklySchedule(first, scheduleThrough, schedule);

        for (InterestRun run : runStates.due(now)) {
            try {
                interest.run(run.getPeriodEndDate(),
                        "weekly-interest:" + run.getPeriodEndDate());
            } catch (RuntimeException failure) {
                log.error("Savings interest run for period ending {} failed; "
                                + "later periods will wait for the retry: {}",
                        run.getPeriodEndDate(), failure.getMessage());
                break;
            }
        }
    }
}
