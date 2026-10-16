package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadItemRepository;


import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripObjectCleanupService {
    private static final int RETRY_BATCH_SIZE = 100;
    private static final Duration CLEANUP_LEASE = Duration.ofMinutes(5);

    private final TripAttachmentRepository attachments;
    private final StoredFileRepository files;
    private final TripRepository trips;
    private final TripDetailPlaceRepository places;
    private final TripAttachmentStorageClient storage;
    private final InitialAttachmentUploadItemRepository uploadItems;
    private final InitialAttachmentUploadTransactionService uploadTransactions;
    private final AdditionalAttachmentUploadItemRepository additionalItems;
    private final AdditionalAttachmentUploadTransactionService additionalTransactions;
    private final AttachmentCleanupScheduleStore cleanupScheduleStore;

    public void processClaim(AttachmentCleanupScheduleStore.Claim claim) {
        List<TripAttachment> found = attachments.findAllForObjectCleanupByIds(
                List.of(claim.attachmentId())
        );

        if (found.isEmpty()) {
            cleanupScheduleStore.complete(claim);
            return;
        }

        TripAttachment attachment = found.getFirst();
        if (attachment.getDeletedAt() == null
                || attachment.getClassificationStatus() == ClassificationStatus.DELETED) {
            cleanupScheduleStore.complete(claim);
            return;
        }

        Instant scheduledAt = attachment.getDeletedAt()
                .plusDays(30)
                .atZone(ZoneId.systemDefault())
                .toInstant();
        if (scheduledAt.isAfter(Instant.now())) {
            cleanupScheduleStore.schedule(attachment.getId(), scheduledAt);
            cleanupScheduleStore.complete(claim);
            return;
        }

        cleanup(found, uploadTransactions.databaseTime().minusDays(30));
        if (attachment.getClassificationStatus() == ClassificationStatus.DELETED) {
            cleanupScheduleStore.complete(claim);
        }
    }

    public void processScheduled() {
        for (int processed = 0; processed < RETRY_BATCH_SIZE; processed++) {
            var claim = cleanupScheduleStore.claim(CLEANUP_LEASE);
            if (claim.isEmpty()) {
                return;
            }

            try {
                processClaim(claim.get());
            } catch (RuntimeException failure) {
                log.warn("예약된 사진 정리에 실패했습니다. attachmentId={}",
                        claim.get().attachmentId(), failure);
            }
        }
    }

    public void retryPending() {
        LocalDateTime before = uploadTransactions.databaseTime().minusDays(30);
        cleanup(attachments.findExpiredPendingCleanup(
                LocalDateTime.now().minusDays(30),
                PageRequest.of(0, RETRY_BATCH_SIZE)
        ), before);
        for (var item : additionalItems.findPendingCleanup(before, PageRequest.of(0, RETRY_BATCH_SIZE))) {
            for (var object : additionalTransactions.cleanupObjects(item)) {
                if (!object.deleted() && object.startedAt() != null && !object.startedAt().isAfter(before)
                        && cleanupKey(object.key())) additionalTransactions.recordObjectDeleted(item.getId(), object.key());
            }
        }
        for (var item : uploadItems.findPendingCleanup(before, PageRequest.of(0, RETRY_BATCH_SIZE))) {
            for (var object : uploadTransactions.cleanupObjects(item)) {
                if (object.deleted() || object.startedAt() == null || object.startedAt().isAfter(before)) {
                    continue;
                }
                if (cleanupKey(object.key())) {
                    uploadTransactions.recordObjectDeleted(item.getId(), object.key());
                }
            }
        }
    }

    private void cleanup(
            List<TripAttachment> pending,
            LocalDateTime before
    ) {
        Map<String, Boolean> results = new HashMap<>();

        for (TripAttachment attachment : pending) {
            List<String> keys = Stream.of(
                            attachment.getFile().getObjectKey(),
                            attachment.getAnalyzeStorageKey(),
                            attachment.getPreviewStorageKey(),
                            attachment.getDisplayStorageKey())
                    .filter(Objects::nonNull)
                    .filter(key -> !key.isBlank())
                    .distinct()
                    .toList();
            var item = uploadItems.findByTripAttachmentId(attachment.getId());
            boolean withinRetention = item.isPresent() && uploadTransactions.cleanupObjects(item.get()).stream()
                    .filter(object -> keys.contains(object.key()))
                    .anyMatch(object -> object.startedAt() != null && object.startedAt().isAfter(before));
            if (withinRetention) {
                continue;
            }
            boolean completed = keys.stream()
                    .map(key -> results.computeIfAbsent(key, this::cleanupKey))
                    .reduce(true, Boolean::logicalAnd);

            if (completed) {
                attachment.markObjectCleanupCompleted();
                attachments.save(attachment);
            }
        }
    }

    private boolean cleanupKey(String objectKey) {
        if (isActivelyReferenced(objectKey)) {
            return false;
        }

        try {
            storage.delete(objectKey);
            return true;
        } catch (RuntimeException failure) {
            log.warn("여행 첨부 객체 삭제에 실패했습니다. stage=object_cleanup, cause={}", failure.getClass().getSimpleName());
            return false;
        }
    }

    private boolean isActivelyReferenced(String objectKey) {
        return files.existsByObjectKeyAndDeletedAtIsNull(objectKey)
                || attachments.existsByAnalyzeStorageKeyAndDeletedAtIsNull(objectKey)
                || attachments.existsByPreviewStorageKeyAndDeletedAtIsNull(objectKey)
                || attachments.existsByDisplayStorageKeyAndDeletedAtIsNull(objectKey)
                || trips.existsByThumbnailKeyAndDeletedAtIsNull(objectKey)
                || places.existsByThumbnailKeyAndDeletedAtIsNull(objectKey);
    }
}
