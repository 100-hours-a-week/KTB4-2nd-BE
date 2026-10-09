package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AttachmentStorageSizeBackfillService {
    private final TripAttachmentRepository attachments;
    private final TripAttachmentStorageClient storage;

    @Transactional
    public void backfillOne(Long attachmentId) {
        TripAttachment attachment = attachments.findById(attachmentId)
                .orElseThrow();
        StoredFile file = attachment.getFile();

        if (attachment.getDeletedAt() != null
                || file.getDeletedAt() != null
                || attachment.getTrip().getDeletedAt() != null
                || attachment.getTrip().getProcessingStatus()
                != ProcessingStatus.COMPLETED) {
            return;
        }

        long originalSize = resolveSize(
                file.getObjectKey(), file.getOriginalSizeBytes());
        long analyzeSize = resolveSize(
                attachment.getAnalyzeStorageKey(),
                attachment.getAnalyzeSizeBytes());
        long previewSize = resolveSize(
                attachment.getPreviewStorageKey(),
                attachment.getPreviewSizeBytes());
        Long displaySize = attachment.getDisplayStorageKey() == null
                ? null
                : resolveSize(
                attachment.getDisplayStorageKey(),
                attachment.getDisplaySizeBytes());

        file.storageSize(originalSize);
        attachment.storageSizes(analyzeSize, previewSize, displaySize);
    }

    private long resolveSize(String objectKey, Long existingSize) {
        if (existingSize != null) {
            return existingSize;
        }
        return storage.size(objectKey);
    }
}