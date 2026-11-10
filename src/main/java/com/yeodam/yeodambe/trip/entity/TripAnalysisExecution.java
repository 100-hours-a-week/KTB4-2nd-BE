package com.yeodam.yeodambe.trip.entity;

import com.yeodam.yeodambe.common.response.ErrorMessage;
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
import java.util.Objects;

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

    public void attachProcessOutbox(Long outboxId) {
        processOutboxId = outboxId;
    }

    public void confirmRemoteTermination(LocalDateTime now) {
        remoteTerminalConfirmedAt = now;
    }

    public void recordProcessPublished(LocalDateTime now) {
        if (processPublishedAt == null) {
            processPublishedAt = now;
        }
    }

    public void receiveResult(String raw, String aiStatus, LocalDateTime now) {
        resultRawPayload = raw;
        resultAiStatus = aiStatus;
        resultReceivedAt = now;
        status = Status.RESULT_APPLYING;
    }

    public void recordConflictingResult(String raw) {
        if (resultConflicts == null) {
            resultConflicts = raw;
        }
    }

    public boolean canClaim(LocalDateTime now) {
        return status == Status.RESULT_APPLYING && endedAt == null && resultRawPayload != null
                && (resultLeaseUntil == null || !resultLeaseUntil.isAfter(now))
                && (resultNextAttemptAt == null || !resultNextAttemptAt.isAfter(now));
    }

    public boolean ownsResult(String token, int attempt, LocalDateTime now) {
        return status == Status.RESULT_APPLYING && endedAt == null
                && Objects.equals(resultClaimToken, token)
                && resultAttempts + 1 == attempt && resultLeaseUntil != null && resultLeaseUntil.isAfter(now);
    }

    public void claimResult(String token, LocalDateTime until) {
        resultClaimToken = token;
        resultLeaseUntil = until;
    }

    public void renewResult(LocalDateTime until) {
        resultLeaseUntil = until;
    }

    public void retryResult(String reason, LocalDateTime now) {
        resultAttempts++;
        resultFailureReason = reason;
        resultClaimToken = null;
        resultLeaseUntil = null;
        resultNextAttemptAt = now.plusSeconds(10);
        if (resultAttempts >= 3) {
            status = Status.APPLY_BLOCKED;
            errorCode = ErrorMessage.RESULT_APPLY_RETRY_EXHAUSTED.name();
        }
    }

    public void finish(Status terminalStatus, String error, boolean remoteUnconfirmed, LocalDateTime now) {
        status = terminalStatus;
        errorCode = error;
        requiresRemoteTerminationConfirmation = remoteUnconfirmed;
        endedAt = now;
        resultClaimToken = null;
        resultLeaseUntil = null;
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
