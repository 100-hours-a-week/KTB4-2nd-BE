package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripStorageObjectKeys;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.exception.UserInternalErrorMessage;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserStatsService {
    private final UserStatsRepository userStats;
    private final TripRepository trips;
    private final TripAttachmentRepository attachments;

    public void refreshFromActiveTrips(Long userId) {
        UserStats stats = userStats.findActiveByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("사용자 통계가 없습니다."));
        List<TripStorageObjectKeys> active = attachments.findAllForStats(
                userId, ProcessingStatus.COMPLETED);

        Map<String, Long> sizesByKey = new HashMap<>();
        for (TripStorageObjectKeys keys : active) {
            addStorageSize(sizesByKey, keys.originalKey(), keys.originalSizeBytes());
            addStorageSize(sizesByKey, keys.analyzeKey(), keys.analyzeSizeBytes());
            addStorageSize(sizesByKey, keys.previewKey(), keys.previewSizeBytes());
            addStorageSize(sizesByKey, keys.displayKey(), keys.displaySizeBytes());
        }

        long storageUsedBytes = sizesByKey.values().stream()
                .mapToLong(Long::longValue)
                .sum();

        stats.replaceActiveTripUsage(
                trips.countByUserIdAndProcessingStatusAndDeletedAtIsNull(
                        userId, ProcessingStatus.COMPLETED),
                active.size(),
                storageUsedBytes);
    }

    private void addStorageSize(Map<String, Long> sizesByKey, String objectKey, Long sizeBytes) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        if (sizeBytes == null) {
            throw new IllegalStateException(
                    UserInternalErrorMessage.ATTACHMENT_STORAGE_SIZE_MISSING.message());
        }
        sizesByKey.putIfAbsent(objectKey, sizeBytes);
    }
}
