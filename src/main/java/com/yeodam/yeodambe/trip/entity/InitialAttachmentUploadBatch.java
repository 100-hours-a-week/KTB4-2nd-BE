package com.yeodam.yeodambe.trip.entity;

import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "initial_attachment_upload_batches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InitialAttachmentUploadBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "upload_batch_id")
    private Long id;

    @Column(name = "upload_id", nullable = false, length = 36)
    private String uploadId;

    @Column(name = "execution_id", nullable = false, length = 36)
    private String executionId;

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
    private InitialAttachmentUploadStatus status;

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

    public InitialAttachmentUploadBatch(
            String uploadId,
            String executionId,
            Long tripId,
            Long userId,
            Integer batchNo,
            Integer totalAttachmentCount,
            Boolean lastBatch
    ) {
        this.uploadId = uploadId;
        this.executionId = executionId;
        this.tripId = tripId;
        this.userId = userId;
        this.batchNo = batchNo;
        this.totalAttachmentCount = totalAttachmentCount;
        this.lastBatch = lastBatch;
        this.status = InitialAttachmentUploadStatus.PENDING;
    }
    public void startProcessing() {
        if (status != InitialAttachmentUploadStatus.PENDING
                && status != InitialAttachmentUploadStatus.FAILED) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        status = InitialAttachmentUploadStatus.PROCESSING;
    }

    public void completeProcessing() {
        if (status != InitialAttachmentUploadStatus.PROCESSING) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        status = InitialAttachmentUploadStatus.COMPLETED;
    }

    public void failProcessing() {
        if (status != InitialAttachmentUploadStatus.PROCESSING) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        status = InitialAttachmentUploadStatus.FAILED;
    }

    public void startAnalysis() {
        if (!lastBatch || status != InitialAttachmentUploadStatus.PROCESSING) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }
        status = InitialAttachmentUploadStatus.ANALYZING;
    }

    public void completeAnalysis() {
        if (!lastBatch || status != InitialAttachmentUploadStatus.ANALYZING) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }
        status = InitialAttachmentUploadStatus.COMPLETED;
    }

    public void failAnalysis() {
        if (!lastBatch || (status != InitialAttachmentUploadStatus.PROCESSING
                && status != InitialAttachmentUploadStatus.ANALYZING)) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }
        status = InitialAttachmentUploadStatus.FAILED;
    }
}
