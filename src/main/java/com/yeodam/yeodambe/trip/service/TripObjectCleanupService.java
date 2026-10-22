package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadItemRepository;
import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.common.logging.WorkflowMdcScope;
import org.slf4j.MDC;


import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripObjectCleanupService {
    private static final int RETRY_BATCH_SIZE = 100;

    private final TripAttachmentRepository attachments;
    private final StoredFileRepository files;
    private final TripRepository trips;
    private final TripDetailPlaceRepository places;
    private final TripAttachmentStorageClient storage;
    private final InitialAttachmentUploadItemRepository uploadItems;
    private final InitialAttachmentUploadTransactionService uploadTransactions;
    private final AdditionalAttachmentUploadItemRepository additionalItems;
    private final AdditionalAttachmentUploadTransactionService additionalTransactions;

    public void retryPending() {
        try (WorkflowMdcScope scope = openCleanupLoggingContext();
             WorkflowLog.Stage scan = startCleanupScanLogging()) {
            cleanupPending();
            logCleanupScanCompleted(scan);
        }
    }

    private WorkflowMdcScope openCleanupLoggingContext() {
        Map<String, String> context = new HashMap<>();
        Map<String, String> current = MDC.getCopyOfContextMap();
        if (current != null) {
            context.putAll(current);
        }
        context.put("workflow", "s3_object_cleanup");
        context.put("run_id", UUID.randomUUID().toString());
        return WorkflowMdcScope.open(context);
    }

    private void cleanupPending() {
        LocalDateTime before = uploadTransactions.databaseTime().minusDays(30);
        cleanup(attachments.findExpiredPendingCleanup(
                LocalDateTime.now().minusDays(30),
                PageRequest.of(0, RETRY_BATCH_SIZE)
        ), before);
        for (var item : additionalItems.findPendingCleanup(before, PageRequest.of(0, RETRY_BATCH_SIZE))) {
            for (var object : additionalTransactions.cleanupObjects(item)) {
                if (!object.deleted() && object.startedAt() != null && !object.startedAt().isAfter(before)
                        && cleanupKey(object.key())) {
                    additionalTransactions.recordObjectDeleted(item.getId(), object.key());
                }
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
            logObjectCleanupSkipped(objectKey);
            return false;
        }

        try {
            storage.delete(objectKey);
            return true;
        } catch (RuntimeException failure) {
            logObjectCleanupFailure(failure);
            return false;
        }
    }

    private WorkflowLog.Stage startCleanupScanLogging() {
        return WorkflowLog.stage("s3_cleanup_scan");
    }

    private void logCleanupScanCompleted(WorkflowLog.Stage scan) {
        scan.success();
    }

    private void logObjectCleanupSkipped(String objectKey) {
        log.atInfo()
                .addKeyValue("event", "s3_object_cleanup_skipped")
                .addKeyValue("result", "skipped")
                .addKeyValue("object_key", objectKey)
                .log("활성 참조가 있어 S3 객체 삭제를 건너뛰었습니다.");
    }

    private void logObjectCleanupFailure(RuntimeException failure) {
        log.warn("여행 첨부 객체 삭제에 실패했습니다. stage=object_cleanup, cause={}",
                failure.getClass().getSimpleName());
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
