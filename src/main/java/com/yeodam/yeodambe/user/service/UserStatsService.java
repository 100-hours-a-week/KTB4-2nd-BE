package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripStorageObjectKeys;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.exception.UserInternalErrorMessage;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserStatsService {
    private final UserStatsRepository userStats;
    private final TripRepository trips;
    private final TripAttachmentRepository attachments;
    private final EntityManager entityManager;

    public void refreshFromActiveTrips(Long userId) {
        refreshActiveTripUsage(userId, lockStats(userId));
    }

    private void refreshActiveTripUsage(
            Long userId,
            UserStats stats
    ) {
        List<TripStorageObjectKeys> active = attachments.findAllForStats(
                userId, ProcessingStatus.COMPLETED);

        Map<String, Long> sizesByKey = new HashMap<>();
        for (TripStorageObjectKeys keys : active) {
            addStorageSize(sizesByKey, keys.originalKey(), keys.originalSizeBytes());
        }

        long storageUsedBytes = sizesByKey.values().stream()
                .mapToLong(Long::longValue)
                .reduce(0L, Math::addExact);

        stats.replaceActiveTripUsage(
                trips.countByUserIdAndProcessingStatusAndDeletedAtIsNull(
                        userId, ProcessingStatus.COMPLETED),
                active.size(),
                storageUsedBytes);
    }

    public void reserveInitialUpload(
            Long userId,
            long bytes
    ) {
        UserStats stats = lockStats(userId);
        refreshActiveTripUsage(userId, stats);
        stats.reserveStorage(bytes);
    }

    public void releaseInitialUploadReservation(
            Long userId,
            long bytes
    ) {
        lockStats(userId).releaseStorageReservation(bytes);
    }

    private UserStats lockStats(Long userId) {
        UserStats stats = userStats.findActiveByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException(UserInternalErrorMessage.USER_STATS_MISSING.message()));
        entityManager.flush();
        entityManager.refresh(stats, LockModeType.PESSIMISTIC_WRITE);
        return stats;
    }

    private void addStorageSize(
            Map<String, Long> sizesByKey,
            String objectKey,
            Long sizeBytes
    ) {
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
