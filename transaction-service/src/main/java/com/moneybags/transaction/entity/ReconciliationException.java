package com.moneybags.transaction.entity;

import com.moneybags.transaction.domain.FinancialEnums.ReconciliationStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity @Table(name = "reconciliation_exceptions")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReconciliationException {
    @Id @Column(name = "exception_id", length = 36) private String id;
    @Column(name = "exception_type", nullable = false, length = 64) private String type;
    @Column(nullable = false, length = 16) private String severity;
    @JsonIgnore @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "transaction_id") private Transaction transaction;
    @Column(name = "business_reference", length = 128) private String businessReference;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ReconciliationStatus status;
    @Lob @Column(nullable = false) private String evidence;
    @Column(name = "assigned_to", length = 64) private String assignedTo;
    @Column(length = 1000) private String resolution;
    @Column(name = "detected_at", nullable = false) private Instant detectedAt;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @Version private long version;
    @PrePersist void create(){ if(id==null)id=UUID.randomUUID().toString(); if(detectedAt==null)detectedAt=Instant.now(); if(status==null)status=ReconciliationStatus.OPEN; }
}
