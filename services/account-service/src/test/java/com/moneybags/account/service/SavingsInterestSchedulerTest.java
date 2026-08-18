package com.moneybags.account.service;

import com.moneybags.account.config.AccountProperties;
import com.moneybags.account.entity.InterestRun;
import com.moneybags.account.entity.InterestRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavingsInterestSchedulerTest {
    @Mock InterestCalculationService interest;
    @Mock InterestRunStateService runStates;
    @Mock SavingsInterestSchedule schedule;
    AccountProperties properties;
    SavingsInterestScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new AccountProperties();
        properties.getInterest().setFirstPeriodEnd(LocalDate.of(2026, 8, 2));
        properties.getInterest().setMaxCatchUpRuns(52);
        scheduler = new SavingsInterestScheduler(interest, runStates, schedule, properties);
    }

    @Test
    void recoversEveryOverdueWeekOldestFirstAndSchedulesTheNextWeek() {
        LocalDate first = LocalDate.of(2026, 8, 2);
        LocalDate second = first.plusWeeks(1);
        when(schedule.latestDuePeriodEnd(any(Instant.class))).thenReturn(second);
        when(runStates.due(any(Instant.class))).thenReturn(List.of(run(first), run(second)));

        scheduler.recoverDueRuns();

        verify(runStates).ensureWeeklySchedule(first, second.plusWeeks(1), schedule);
        InOrder order = inOrder(interest);
        order.verify(interest).run(first, "weekly-interest:" + first);
        order.verify(interest).run(second, "weekly-interest:" + second);
    }

    @Test
    void sundayPeriodBecomesDueAtMonday0010UtcEvenIfThatInstantWasMissed() {
        SavingsInterestSchedule realSchedule = new SavingsInterestSchedule(properties);

        assertThat(realSchedule.latestDuePeriodEnd(
                Instant.parse("2026-08-17T00:09:59Z")))
                .isEqualTo(LocalDate.of(2026, 8, 9));
        assertThat(realSchedule.latestDuePeriodEnd(
                Instant.parse("2026-08-17T00:10:00Z")))
                .isEqualTo(LocalDate.of(2026, 8, 16));
        assertThat(realSchedule.latestDuePeriodEnd(
                Instant.parse("2026-08-19T12:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 8, 16));
    }

    private InterestRun run(LocalDate periodEnd) {
        return InterestRun.builder().runId("RUN-" + periodEnd)
                .periodStartDate(periodEnd.minusDays(6)).periodEndDate(periodEnd)
                .scheduledAt(Instant.EPOCH).status(InterestRunStatus.SCHEDULED).build();
    }
}
