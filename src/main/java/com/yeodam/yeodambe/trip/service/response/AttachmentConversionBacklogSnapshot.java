package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public record AttachmentConversionBacklogSnapshot(
        Instant sampledAt,
        long sampledAtNanos,
        long migrationPendingBatches,
        Map<Lane, Backlog> lanes
) {
    public record Backlog(
            long remainingPhotos,
            long remainingBytes,
            Duration oldestUnfinishedAge
    ) {
    }
}
