package com.yeodam.yeodambe.trip.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "initial_attachment_upload_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InitialAttachmentUploadItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "upload_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "upload_batch_id", nullable = false)
    private InitialAttachmentUploadBatch batch;

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

    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private LocalDateTime createdAt;

    public InitialAttachmentUploadItem(
            InitialAttachmentUploadBatch batch,
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
}