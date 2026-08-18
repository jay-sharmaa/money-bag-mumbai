package com.moneybags.account.service;

import com.moneybags.account.config.AccountProperties;
import com.moneybags.account.entity.InterestRunStatus;
import com.moneybags.account.repository.InterestRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({InterestRunStateService.class, AccountProperties.class})
class InterestRunStateServiceTest {
    @Autowired InterestRunStateService states;
    @Autowired InterestRunRepository runs;

    @Test
    void failedAndInterruptedRunsRemainSavedAndBecomeRetryable() {
        LocalDate periodEnd = LocalDate.of(2026, 8, 16);
        Instant scheduledAt = Instant.parse("2026-08-17T00:10:00Z");
        String runId = states.claim(periodEnd, scheduledAt).run().getRunId();

        states.fail(runId, new IllegalStateException("transaction service unavailable"));

        var failed = runs.findById(runId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(InterestRunStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("transaction service unavailable");
        assertThat(states.due(Instant.now())).isEmpty();

        failed.setNextAttemptAt(Instant.now().minusSeconds(1));
        runs.saveAndFlush(failed);
        assertThat(states.due(Instant.now())).extracting(run -> run.getPeriodEndDate())
                .containsExactly(periodEnd);

        var retried = states.claim(periodEnd, scheduledAt).run();
        assertThat(retried.getStatus()).isEqualTo(InterestRunStatus.RUNNING);
        assertThat(retried.getAttempts()).isEqualTo(2);

        retried.setStartedAt(Instant.now().minusSeconds(31 * 60L));
        runs.saveAndFlush(retried);
        assertThat(states.due(Instant.now())).extracting(run -> run.getPeriodEndDate())
                .containsExactly(periodEnd);
    }
}
