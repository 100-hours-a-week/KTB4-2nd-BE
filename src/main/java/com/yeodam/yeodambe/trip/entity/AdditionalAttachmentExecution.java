package com.yeodam.yeodambe.trip.entity;

import com.yeodam.yeodambe.user.exception.UserInternalErrorMessage;

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
@Table(name = "additional_attachment_executions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdditionalAttachmentExecution {

    @Id
    @Column(name = "execution_id", nullable = false, length = 36)
    private String executionId;

    @Column(name = "trip_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long tripId;

    @Column(name = "user_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status = Status.UPLOADING;

    @Column(name = "total_attachment_count", nullable = false)
    private int totalAttachmentCount;

    @Column(name = "analysis_attachment_count")
    private Integer analysisAttachmentCount;

    @Column(name = "input_closed", nullable = false)
    private boolean inputClosed;

    @Column(name = "last_upload_activity_at", nullable = false)
    private LocalDateTime lastUploadActivityAt;

    @Column(name = "reserved_bytes", nullable = false)
    private long reservedBytes;

    @Column(name = "accounted_bytes", nullable = false)
    private long accountedBytes;

    @Column(name = "reservation_released", nullable = false)
    private boolean reservationReleased;

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

    @Column(name = "result_attempt_started_at")
    private LocalDateTime resultAttemptStartedAt;

    @Column(name = "result_failed_at")
    private LocalDateTime resultFailedAt;

    @Column(name = "result_failure_reason", length = 255)
    private String resultFailureReason;

    @Column(name = "result_claim_token", length = 36)
    private String resultClaimToken;

    @Column(name = "result_lease_until")
    private LocalDateTime resultLeaseUntil;

    @Column(name = "result_next_attempt_at")
    private LocalDateTime resultNextAttemptAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private LocalDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private LocalDateTime updatedAt;

    public AdditionalAttachmentExecution(
            String executionId,
            Long tripId,
            Long userId,
            int totalAttachmentCount,
            LocalDateTime now
    ) {
        this.executionId = executionId;
        this.tripId = tripId;
        this.userId = userId;
        this.totalAttachmentCount = totalAttachmentCount;
        this.lastUploadActivityAt = now;
    }

    public boolean isTerminal() {
        return endedAt != null;
    }

    public void recordUploadActivity(LocalDateTime now) {
        lastUploadActivityAt = now;
    }

    public void reserve(long bytes) {
        reservedBytes = Math.addExact(reservedBytes, bytes);
    }

    public void closeInput() {
        if (isTerminal() || inputClosed) return;
        inputClosed = true;
        status = Status.IMAGE_PROCESSING;
    }

    public void attachProcessOutbox(
            Long outboxId,
            int analysisAttachmentCount
    ) {
        if (processOutboxId == null) {
            this.processOutboxId = outboxId;
            this.analysisAttachmentCount = analysisAttachmentCount;
        }
    }

    public void recordProcessPublished(LocalDateTime now) {
        if (processPublishedAt == null) {
            processPublishedAt = now;
        }

        if (!isTerminal() && resultRawPayload == null) {
            status = Status.AI_QUEUED;
        }
    }

    public void receiveResult(
            String rawPayload,
            String aiStatus,
            LocalDateTime now
    ) {
        if (isTerminal() || resultRawPayload != null) {
            return;
        }

        resultRawPayload = rawPayload;
        resultAiStatus = aiStatus;
        resultReceivedAt = now;
        status = Status.RESULT_APPLYING;
    }

    public void recordConflictingResult(String rawPayload) {
        if (resultConflicts == null) {
            resultConflicts = rawPayload;
        }
    }

    public void claimResult(String token, LocalDateTime until) {
        resultClaimToken = token;
        resultLeaseUntil = until;
    }

    public void startResultAttempt(LocalDateTime now) {
        resultAttemptStartedAt = now;
        resultAttempts++;
    }

    public void renewResultLease(LocalDateTime until) {
        resultLeaseUntil = until;
    }

    public void recordResultFailure(String reason, LocalDateTime now) {
        resultFailureReason = reason;
        resultFailedAt = now;
    }

    public void retryResult(LocalDateTime next) {
        resultClaimToken = null;
        resultLeaseUntil = null;
        resultNextAttemptAt = next;
    }

    public void accountPhoto(long bytes) {
        if (reservationReleased || bytes < 0 || bytes > reservedBytes) {
            throw new IllegalStateException(
                    UserInternalErrorMessage.USER_STATS_NEGATIVE.message());
        }
        accountedBytes = Math.addExact(accountedBytes, bytes);
        reservedBytes -= bytes;
    }

    public void releaseReservation(long accountedBytes) {
        if (reservationReleased) {
            return;
        }

        reservationReleased = true;
        this.accountedBytes = accountedBytes;
        reservedBytes = 0;
    }

    public void finish(
            Status status,
            String errorCode,
            LocalDateTime now
    ) {
        if (isTerminal()) {
            return;
        }

        this.status = status;
        this.errorCode = errorCode;
        this.endedAt = now;
        resultClaimToken = null;
        resultLeaseUntil = null;
    }

    public enum Status {
        UPLOADING,
        IMAGE_PROCESSING,
        AI_QUEUED,
        RESULT_APPLYING,
        COMPLETED,
        FAILED,
        CANCELED
    }
}
