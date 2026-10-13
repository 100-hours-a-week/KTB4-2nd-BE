package com.yeodam.yeodambe.trip.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "additional_attachment_upload_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdditionalAttachmentUploadItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "upload_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "upload_batch_id", nullable = false)
    private AdditionalAttachmentUploadBatch batch;

    @Column(name = "file_order", nullable = false)
    private Integer fileOrder;

    @Column(name = "original_file_name", nullable = false, length = 255)
    private String originalFileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(name = "trip_attachment_id")
    private Long tripAttachmentId;

    @Column(name = "taken_at_with_offset", length = 40)
    private String takenAtWithOffset;

    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private LocalDateTime createdAt;

    @Column(name = "conversion_attempts", nullable = false)
    private int conversionAttempts;

    @Column(name = "conversion_started_at")
    private LocalDateTime conversionStartedAt;

    @Column(name = "conversion_next_attempt_at")
    private LocalDateTime conversionNextAttemptAt;

    @Column(name = "derived_json", columnDefinition = "LONGTEXT")
    private String derivedJson;

    @Column(name = "cleanup_json", columnDefinition = "LONGTEXT")
    private String cleanupJson;

    @Column(name = "cleanup_started_at")
    private LocalDateTime cleanupStartedAt;

    public AdditionalAttachmentUploadItem(
            AdditionalAttachmentUploadBatch batch,
            Integer fileOrder,
            String originalFileName,
            String contentType,
            Long sizeBytes,
            String objectKey
    ) {
        this.batch = batch;
        this.fileOrder = fileOrder;
        this.originalFileName = originalFileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.objectKey = objectKey;
    }

    public void linkAttachment(Long tripAttachmentId) {
        this.tripAttachmentId = tripAttachmentId;
    }

    public void recordTakenAt(OffsetDateTime takenAt) {
        takenAtWithOffset = takenAt == null ? null : takenAt.toString();
    }

    public void startConversion(LocalDateTime now) {
        conversionAttempts++;
        conversionStartedAt = now;
    }

    public void conversionCompleted(String derivedJson) {
        this.derivedJson = derivedJson;
        conversionStartedAt = null;
        conversionNextAttemptAt = null;
    }

    public void conversionFailed(LocalDateTime next) {
        conversionStartedAt = null;
        conversionNextAttemptAt = next;
    }

    public void recordCleanup(
            String json,
            LocalDateTime earliest
    ) {
        cleanupJson = json;
        cleanupStartedAt = earliest;
    }
}
