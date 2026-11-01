package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.service.DerivedPhotoKeys;

import java.time.LocalDateTime;
import java.util.List;

public record AttachmentConversionClaim(
        String taskId,
        String claimToken,
        Kind kind,
        Lane lane,
        Long batchId,
        String uploadId,
        String executionId,
        Long tripId,
        Long userId,
        String requestId,
        List<Photo> photos
) {
    public record Photo(
            Long itemId,
            String contentType,
            String objectKey,
            long sizeBytes,
            int attempts,
            LocalDateTime nextAttemptAt,
            DerivedPhotoKeys derived
    ) {
    }

    public record Attempt(
            Long itemId,
            int attemptNo,
            DerivedPhotoKeys plannedKeys
    ) {
    }
}
