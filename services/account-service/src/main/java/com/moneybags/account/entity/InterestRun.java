package com.moneybags.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "interest_runs", uniqueConstraints =
        @UniqueConstraint(name = "uk_interest_run_period_end", columnNames = "period_end_date"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterestRun {

    @Id
    @Column(name = "run_id", length = 36)
    private String runId;

    @Column(name = "period_start_date", nullable = false)
    private LocalDate periodStartDate;

    @Column(name = "period_end_date", nullable = false)
    private LocalDate periodEndDate;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InterestRunStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "accounts_evaluated")
    private Integer accountsEvaluated;

    @Column(name = "accruals_created")
    private Integer accrualsCreated;

    @Column(name = "payouts_queued")
    private Integer payoutsQueued;

    @Column(name = "accounts_skipped")
    private Integer accountsSkipped;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void create() {
        Instant now = Instant.now();
        if (runId == null) runId = UUID.randomUUID().toString();
        if (status == null) status = InterestRunStatus.SCHEDULED;
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void update() {
        updatedAt = Instant.now();
    }
}
