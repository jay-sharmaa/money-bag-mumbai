package com.moneybags.transaction.entity;

import com.moneybags.transaction.domain.FinancialEnums.OutboxStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "outbox_events")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class OutboxEvent {
    @Id @Column(name = "event_id", length = 36) private String id;
    @Column(name = "aggregate_type", nullable = false, length = 40) private String aggregateType;
    @Column(name = "aggregate_id", nullable = false, length = 36) private String aggregateId;
    @Column(name = "event_type", nullable = false, length = 64) private String eventType;
    @Column(name = "deduplication_key", nullable = false, unique = true, length = 160) private String deduplicationKey;
    @Lob @Column(nullable = false) private String payload;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private OutboxStatus status;
    @Column(nullable = false) private int attempts;
    @Column(name = "next_attempt_at") private Instant nextAttemptAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "last_error", length = 500) private String lastError;
    @Version private long version;
    @PrePersist void create(){ if(id==null)id=UUID.randomUUID().toString(); if(createdAt==null)createdAt=Instant.now(); if(status==null)status=OutboxStatus.PENDING; }
}
