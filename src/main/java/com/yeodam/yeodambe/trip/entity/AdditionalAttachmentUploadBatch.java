package com.yeodam.yeodambe.trip.entity;

import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "additional_attachment_upload_batches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdditionalAttachmentUploadBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "upload_batch_id")
    private Long id;

    @Column(name = "upload_id", nullable = false, length = 36)
    private String uploadId;

    @Column(name = "addition_id", nullable = false, length = 36)
    private String additionId;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "batch_no", nullable = false)
    private Integer batchNo;

    @Column(name = "total_attachment_count", nullable = false)
    private Integer totalAttachmentCount;

    @Column(name = "is_last_batch", nullable = false)
    private Boolean lastBatch;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AdditionalAttachmentUploadStatus status;

    @Column(name = "worker_token", length = 36)
    private String workerToken;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

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

    public AdditionalAttachmentUploadBatch(
            String uploadId,
            String additionId,
            Long tripId,
            Long userId,
            Integer batchNo,
            Integer totalAttachmentCount,
            Boolean lastBatch
    ) {
        this.uploadId = uploadId;
        this.additionId = additionId;
        this.tripId = tripId;
        this.userId = userId;
        this.batchNo = batchNo;
        this.totalAttachmentCount = totalAttachmentCount;
        this.lastBatch = lastBatch;
        this.status = AdditionalAttachmentUploadStatus.PENDING;
    }

    public void markVerified() {
        if (status == AdditionalAttachmentUploadStatus.VERIFIED) {
            return;
        }

        if (status != AdditionalAttachmentUploadStatus.PENDING) {
            throw new TripAttachmentAddNotAllowedException();
        }

        status = AdditionalAttachmentUploadStatus.VERIFIED;
    }

    public void claimConversion(String token) {
        if (status != AdditionalAttachmentUploadStatus.VERIFIED || workerToken != null) {
            throw new TripAttachmentAddNotAllowedException();
        }
        status = AdditionalAttachmentUploadStatus.CONVERTING;
        workerToken = token;
    }

    public boolean isConversionOwner(String token) {
        return status == AdditionalAttachmentUploadStatus.CONVERTING
                && workerToken != null && workerToken.equals(token);
    }

    public void releaseConversion(String token) {
        if (!isConversionOwner(token)) throw new TripAttachmentAddNotAllowedException();
        status = AdditionalAttachmentUploadStatus.VERIFIED;
        workerToken = null;
    }

    public void markPrepared() {
        if (status != AdditionalAttachmentUploadStatus.VERIFIED
                && status != AdditionalAttachmentUploadStatus.PREPARED) {
            throw new TripAttachmentAddNotAllowedException();
        }
        status = AdditionalAttachmentUploadStatus.PREPARED;
    }

    public void completeAddition() {
        if (status != AdditionalAttachmentUploadStatus.PREPARED) {
            throw new TripAttachmentAddNotAllowedException();
        }
        status = AdditionalAttachmentUploadStatus.COMPLETED;
    }

    public void failAddition() {
        if (status != AdditionalAttachmentUploadStatus.PREPARED) {
            throw new TripAttachmentAddNotAllowedException();
        }
        status = AdditionalAttachmentUploadStatus.FAILED;
    }
}
