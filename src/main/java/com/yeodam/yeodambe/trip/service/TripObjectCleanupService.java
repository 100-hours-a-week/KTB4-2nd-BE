package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    @Transactional
    public void process(Collection<Long> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) return;
        cleanup(attachments.findPendingCleanupByIds(attachmentIds));
    }

    @Transactional
    public void retryPending() {
        cleanup(attachments.findPendingCleanup(PageRequest.of(0, RETRY_BATCH_SIZE)));
    }

    private void cleanup(List<TripAttachment> pending) {
        Map<String, Boolean> results = new HashMap<>();

        for (TripAttachment attachment : pending) {
            boolean completed = Stream.of(
                            attachment.getFile().getObjectKey(),
                            attachment.getAnalyzeStorageKey(),
                            attachment.getPreviewStorageKey())
                    .distinct()
                    .map(key -> results.computeIfAbsent(key, this::cleanupKey))
                    .reduce(true, Boolean::logicalAnd);

            if (completed) attachment.markObjectCleanupCompleted();
        }
    }

    private boolean cleanupKey(String objectKey) {
        if (isActivelyReferenced(objectKey)) return true;

        try {
            storage.delete(objectKey);
            return true;
        } catch (RuntimeException failure) {
            log.warn("여행 첨부 객체 삭제에 실패했습니다. objectKey={}", objectKey, failure);
            return false;
        }
    }

    private boolean isActivelyReferenced(String objectKey) {
        return files.existsByObjectKeyAndDeletedAtIsNull(objectKey)
                || attachments.existsByAnalyzeStorageKeyAndDeletedAtIsNull(objectKey)
                || attachments.existsByPreviewStorageKeyAndDeletedAtIsNull(objectKey)
                || trips.existsByThumbnailKeyAndDeletedAtIsNull(objectKey)
                || places.existsByThumbnailKeyAndDeletedAtIsNull(objectKey);
    }
}
