package com.yeodam.yeodambe.trip.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "trip_creation_executions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TripCreationExecution {
    @Id
    @Column(name = "execution_id")
    private String executionId;

    @Column(name = "trip_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long tripId;

    @Column(name = "user_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status = Status.UPLOADING;

    @Column(name = "status_version", nullable = false)
    private long statusVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "display_status", nullable = false, length = 24)
    private TripDisplayStatus displayStatus = TripDisplayStatus.PROCESSING;

    @Column(name = "current_step", columnDefinition = "TEXT")
    private String currentStep;

    @Column(name = "total_attachment_count", nullable = false)
    private int totalAttachmentCount;

    @Column(name = "input_closed", nullable = false)
    private boolean inputClosed;

    @Column(name = "last_upload_activity_at", nullable = false)
    private LocalDateTime lastUploadActivityAt;

    @Column(name = "process_published_at")
    private LocalDateTime processPublishedAt;

    @Column(name = "reserved_bytes", nullable = false)
    private long reservedBytes;

    @Column(name = "accounted_bytes", nullable = false)
    private long accountedBytes;

    @Column(name = "reservation_released", nullable = false)
    private boolean reservationReleased;

    @Column(name = "process_outbox_id", length = 36)
    private String processOutboxId;

    @Column(name = "process_payload", columnDefinition = "LONGTEXT")
    private String processPayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "process_delivery_status", nullable = false, length = 20)
    private InitialAttachmentDeliveryStatus processDeliveryStatus = InitialAttachmentDeliveryStatus.PENDING;

    @Column(name = "process_attempts", nullable = false)
    private int processAttempts;

    @Column(name = "process_claim_token", length = 36)
    private String processClaimToken;

    @Column(name = "process_lease_until")
    private LocalDateTime processLeaseUntil;

    @Column(name = "process_next_attempt_at")
    private LocalDateTime processNextAttemptAt;

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

    @Column(name = "cancel_pending", nullable = false)
    private boolean cancelPending;

    public TripCreationExecution(
            String executionId,
            Long tripId,
            Long userId,
            int totalAttachmentCount
    ) {
        this.executionId = executionId;
        this.tripId = tripId;
        this.userId = userId;
        this.totalAttachmentCount = totalAttachmentCount;
        this.lastUploadActivityAt = LocalDateTime.now();
    }

    public enum Status {
        UPLOADING, IMAGE_PROCESSING, AI_QUEUED, RESULT_APPLYING, COMPLETED, FAILED, CANCELED
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
        if (isTerminal() || inputClosed) {
            return;
        }
        inputClosed = true;
        changeStatus(Status.IMAGE_PROCESSING);
    }

    public void recordProcessPublished(LocalDateTime now) {
        if (processPublishedAt == null) {
            processPublishedAt = now;
        }
        if (!isTerminal() && resultRawPayload == null) {
            changeStatus(Status.AI_QUEUED);
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
        changeStatus(Status.RESULT_APPLYING);
    }

    public void recordConflictingResult(String rawPayload) {
        if (resultConflicts == null) {
            resultConflicts = rawPayload;
        }
    }

    public void enqueueProcess(String payload) {
        if (processOutboxId == null) {
            processOutboxId = UUID.randomUUID().toString();
            processPayload = payload;
        }
    }

    public void processSent(LocalDateTime now) {
        processDeliveryStatus = InitialAttachmentDeliveryStatus.SENT;
        processClaimToken = null;
        processLeaseUntil = null;
        recordProcessPublished(now);
    }

    public void claimResult(
            String token,
            LocalDateTime until
    ) {
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

    public void recordResultFailure(
            String reason,
            LocalDateTime now
    ) {
        resultFailureReason = reason;
        resultFailedAt = now;
    }

    public void retryResult(LocalDateTime next) {
        resultClaimToken = null;
        resultLeaseUntil = null;
        resultNextAttemptAt = next;
    }

    public void releaseReservation(long accountedBytes) {
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
        changeStatus(status);
        this.errorCode = errorCode;
        this.endedAt = now;
        resultClaimToken = null;
        resultLeaseUntil = null;
    }

    public void requestCancellation() {
        cancelPending = processOutboxId != null;
    }

    public void cancellationDelivered() {
        cancelPending = false;
    }

    private void changeStatus(Status nextStatus) {
        if (this.status == nextStatus) {
            return;
        }

        this.status = nextStatus;

        switch (nextStatus) {
            case RESULT_APPLYING ->
                    updateDisplayStatus(TripDisplayStatus.FINALIZING, null);
            case COMPLETED ->
                    updateDisplayStatus(TripDisplayStatus.COMPLETED, null);
            case FAILED ->
                    updateDisplayStatus(TripDisplayStatus.FAILED, null);
            case CANCELED ->
                    updateDisplayStatus(TripDisplayStatus.CANCELED, null);
            default -> {
            }
        }
    }

    public boolean updateDisplayStatus(
            TripDisplayStatus nextStatus,
            String nextStep
    ) {
        if (isTerminal()) {
            return false;
        }

        if (this.displayStatus == TripDisplayStatus.FINALIZING
                && nextStatus == TripDisplayStatus.PROCESSING) {
            return false;
        }

        if (this.displayStatus == nextStatus
                && Objects.equals(this.currentStep, nextStep)) {
            return false;
        }

        this.statusVersion = Math.incrementExact(this.statusVersion);
        this.displayStatus = nextStatus;
        this.currentStep = nextStep;

        return true;
    }
}
