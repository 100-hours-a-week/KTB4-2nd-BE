package com.yeodam.yeodambe.trip.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "attachment_conversion_tasks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttachmentConversionTask {

    @Id
    @Column(name = "task_id", length = 36)
    private String id;

    @Column(name = "initial_batch_id", updatable = false)
    private Long initialBatchId;

    @Column(name = "additional_batch_id", updatable = false)
    private Long additionalBatchId;

    @Column(name = "trip_id", nullable = false, updatable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long tripId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Lane lane;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.WAITING;

    @Column(name = "total_photos", nullable = false, updatable = false)
    private long totalPhotos;

    @Column(name = "remaining_photos", nullable = false)
    private long remainingPhotos;

    @Column(name = "total_bytes", nullable = false, updatable = false)
    private long totalBytes;

    @Column(name = "remaining_bytes", nullable = false)
    private long remainingBytes;

    @Column(name = "claim_token", length = 36)
    private String claimToken;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "current_item_id")
    private Long currentItemId;

    @Column(name = "current_attempt_no")
    private Integer currentAttemptNo;

    @Column(name = "current_attempt_token", length = 36)
    private String currentAttemptToken;

    @Column(name = "latest_delivery_no", nullable = false)
    private long latestDeliveryNo = 1;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public AttachmentConversionTask(
            String id,
            Kind kind,
            Long batchId,
            Long tripId,
            Lane lane,
            long totalPhotos,
            long totalBytes
    ) {
        this.id = id;
        this.kind = kind;
        this.initialBatchId = kind == Kind.INITIAL ? batchId : null;
        this.additionalBatchId = kind == Kind.ADDITIONAL ? batchId : null;
        this.tripId = tripId;
        this.lane = lane;
        this.totalPhotos = totalPhotos;
        this.remainingPhotos = totalPhotos;
        this.totalBytes = totalBytes;
        this.remainingBytes = totalBytes;
    }

    public boolean isTerminal() {
        return status == Status.SUCCEEDED || status == Status.CANCELED || status == Status.FAILED;
    }

    public boolean isClaimOwner(String token, LocalDateTime now) {
        return status == Status.RUNNING && claimToken != null && claimToken.equals(token)
                && leaseUntil != null && leaseUntil.isAfter(now);
    }

    public void claim(String token, LocalDateTime until) {
        status = Status.RUNNING;
        claimToken = token;
        leaseUntil = until;
        nextAttemptAt = null;
    }

    public void renewLease(LocalDateTime until) {
        leaseUntil = until;
    }

    public boolean hasCurrentAttempt(Long itemId, String token) {
        return currentItemId != null && Objects.equals(currentItemId, itemId)
                && currentAttemptToken != null && Objects.equals(currentAttemptToken, token);
    }

    public void startPhotoAttempt(Long itemId, int attemptNo, String token) {
        currentItemId = itemId;
        currentAttemptNo = attemptNo;
        currentAttemptToken = token;
        nextAttemptAt = null;
    }

    public void clearPhotoAttempt() {
        currentItemId = null;
        currentAttemptNo = null;
        currentAttemptToken = null;
    }

    public void retryPhoto(LocalDateTime next) {
        clearPhotoAttempt();
        nextAttemptAt = next;
    }

    public void recordRemaining(long photos, long bytes) {
        remainingPhotos = photos;
        remainingBytes = bytes;
    }

    public void finish(Status terminal) {
        if (terminal != Status.SUCCEEDED && terminal != Status.CANCELED && terminal != Status.FAILED) {
            throw new IllegalArgumentException("변환 작업의 종료 상태가 필요합니다.");
        }
        status = terminal;
        claimToken = null;
        leaseUntil = null;
        nextAttemptAt = null;
        clearPhotoAttempt();
        if (terminal == Status.SUCCEEDED) {
            recordRemaining(0, 0);
        }
    }

    public enum Kind {
        INITIAL,
        ADDITIONAL
    }

    public enum Lane {
        LIGHT,
        HEIC
    }

    public enum Status {
        WAITING,
        RUNNING,
        SUCCEEDED,
        CANCELED,
        FAILED
    }
}
