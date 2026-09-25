package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripStorageObjectKeys;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class UserStatsService {
    private final UserStatsRepository userStats;
    private final TripRepository trips;
    private final TripAttachmentRepository attachments;
    private final TripAttachmentStorageClient storage;

    public void refreshFromActiveTrips(Long userId) {
        UserStats stats = userStats.findActiveByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("사용자 통계가 없습니다."));
        List<TripStorageObjectKeys> active = attachments.findAllForStats(
                userId, ProcessingStatus.COMPLETED);

        long storageUsedBytes = active.stream()
                .flatMap(keys -> Stream.of(
                        keys.originalKey(), keys.analyzeKey(), keys.previewKey()))
                .distinct()
                .mapToLong(storage::size)
                .sum();

        stats.replaceActiveTripUsage(
                trips.countByUserIdAndProcessingStatusAndDeletedAtIsNull(
                        userId, ProcessingStatus.COMPLETED),
                active.size(),
                storageUsedBytes);
    }
}
