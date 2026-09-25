package com.yeodam.yeodambe.trip.entity;

import com.yeodam.yeodambe.file.entity.StoredFile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "trip_attachments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TripAttachment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "trip_attachment_id")
    private Long id;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false, insertable = false, updatable = false)
    private Trip trip;

    @Column(name = "trip_place_id")
    private Long tripPlaceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_place_id", insertable = false, updatable = false)
    private TripDetailPlace tripPlace;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false, insertable = false, updatable = false)
    private StoredFile file;

    @Enumerated(EnumType.STRING)
    @Column(name = "region_origin", nullable = false, length = 20)
    private RegionOrigin regionOrigin;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue", nullable = false, length = 40)
    private AttachmentIssue issue;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification_status", nullable = false, length = 20)
    private ClassificationStatus classificationStatus = ClassificationStatus.UNCLASSIFIED;

    @Column(name = "analyze_storage_key", nullable = false, length = 500)
    private String analyzeStorageKey;

    @Column(name = "preview_storage_key", nullable = false, length = 500)
    private String previewStorageKey;

    @Column(name = "evaluation")
    private Integer evaluation;

    @Column(name = "taken_at")
    private LocalDateTime takenAt;

    @Column(name = "latitude", precision = 10, scale = 8)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 11, scale = 8)
    private BigDecimal longitude;

    @Column(name = "device_model", length = 100)
    private String deviceModel;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public static TripAttachment initial(
            Long tripId, Long fileId, String analyzeKey, String previewKey
    ) {
        TripAttachment attachment = new TripAttachment();
        attachment.tripId = tripId;
        attachment.fileId = fileId;
        attachment.regionOrigin = RegionOrigin.UNKNOWN;
        attachment.issue = AttachmentIssue.NONE;
        attachment.classificationStatus = ClassificationStatus.UNCLASSIFIED;
        attachment.analyzeStorageKey = analyzeKey;
        attachment.previewStorageKey = previewKey;
        return attachment;
    }

    public void classify(Long placeId, RegionOrigin origin, LocalDateTime takenAt,
                         BigDecimal latitude, BigDecimal longitude, Integer evaluation) {
        this.tripPlaceId = placeId;
        this.regionOrigin = origin;
        this.classificationStatus = ClassificationStatus.ACTIVE;
        this.issue = AttachmentIssue.NONE;
        this.takenAt = takenAt;
        this.latitude = latitude;
        this.longitude = longitude;
        this.evaluation = evaluation;
    }

    public void unclassify(AttachmentIssue issue, RegionOrigin origin, LocalDateTime takenAt,
                           BigDecimal latitude, BigDecimal longitude, Integer evaluation) {
        this.issue = issue;
        this.regionOrigin = origin;
        this.classificationStatus = ClassificationStatus.UNCLASSIFIED;
        this.takenAt = takenAt;
        this.latitude = latitude;
        this.longitude = longitude;
        this.evaluation = evaluation;
    }

    public void originalMetadata(OffsetDateTime takenAt, BigDecimal latitude,
                                 BigDecimal longitude, String deviceModel) {
        this.takenAt = takenAt == null ? null : takenAt.toLocalDateTime();
        this.latitude = latitude;
        this.longitude = longitude;
        this.deviceModel = deviceModel;
        if (latitude != null && longitude != null) this.regionOrigin = RegionOrigin.EXIF;
    }

    public void softDelete(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public void markObjectCleanupCompleted() {
        if (deletedAt == null) {
            throw new IllegalStateException("삭제되지 않은 첨부는 객체 정리를 완료할 수 없습니다.");
        }
        this.classificationStatus = ClassificationStatus.DELETED;
    }
}
