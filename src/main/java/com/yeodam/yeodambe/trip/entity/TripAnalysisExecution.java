package com.yeodam.yeodambe.trip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "trip_analysis_executions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TripAnalysisExecution {
    @Id
    @Column(name = "execution_id", nullable = false, length = 36)
    private String executionId;

    @Column(name = "trip_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long tripId;

    @Column(name = "owner_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status = Status.AI_REQUESTED;

    @Column(name = "process_outbox_id")
    private Long processOutboxId;

    @Column(name = "process_published_at")
    private LocalDateTime processPublishedAt;

    @Column(name = "result_raw_payload", columnDefinition = "LONGTEXT")
    private String resultRawPayload;

    @Column(name = "result_ai_status", length = 20)
    private String resultAiStatus;

    @Column(name = "result_received_at")
    private LocalDateTime resultReceivedAt;

    @Column(name = "result_conflicts", columnDefinition = "LONGTEXT")
    private String resultConflicts;

    @Column(name = "result_attempts", nullable = false)
    private int resultAttempts;

    @Column(name = "result_claim_token", length = 36)
    private String resultClaimToken;

    @Column(name = "result_lease_until")
    private LocalDateTime resultLeaseUntil;

    @Column(name = "result_next_attempt_at")
    private LocalDateTime resultNextAttemptAt;

    @Column(name = "result_failure_reason", length = 255)
    private String resultFailureReason;

    @Column(name = "requires_remote_termination_confirmation", nullable = false)
    private boolean requiresRemoteTerminationConfirmation;

    @Column(name = "remote_terminal_confirmed_at")
    private LocalDateTime remoteTerminalConfirmedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public TripAnalysisExecution(String executionId, Long tripId, Long ownerId) {
        this.executionId = executionId;
        this.tripId = tripId;
        this.ownerId = ownerId;
    }

    public enum Status {
        AI_REQUESTED,
        RESULT_APPLYING,
        APPLY_BLOCKED,
        COMPLETED,
        FAILED,
        CANCELED
    }
}
