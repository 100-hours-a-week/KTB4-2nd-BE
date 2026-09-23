package com.yeodam.yeodambe.file.entity;

import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "files")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoredFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "file_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, insertable = false, updatable = false)
    private User user;

    @OneToMany(mappedBy = "file")
    private List<TripAttachment> tripAttachments = new ArrayList<>();

    @Column(name = "original_file_name", nullable = false, length = 255)
    private String originalFileName;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "upload_status", nullable = false, length = 20)
    private UploadStatus uploadStatus = UploadStatus.PENDING;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public static StoredFile uploaded(Long userId, String originalFileName,
                                      String objectKey, String detectedMimeType) {
        if (originalFileName == null || originalFileName.isBlank()
                || originalFileName.length() > 255) {
            throw new InvalidAttachmentUploadException();
        }

        if (userId == null || objectKey == null || objectKey.isBlank()
                || objectKey.length() > 500
                || detectedMimeType == null || detectedMimeType.isBlank()
                || detectedMimeType.length() > 100) {
            throw new IllegalArgumentException("저장할 파일 정보가 올바르지 않습니다.");
        }

        StoredFile file = new StoredFile();
        file.userId = userId;
        file.originalFileName = originalFileName;
        file.objectKey = objectKey;
        file.mimeType = detectedMimeType;
        file.uploadStatus = UploadStatus.READY;
        file.uploadedAt = LocalDateTime.now();
        return file;
    }

    public void softDelete(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

}
