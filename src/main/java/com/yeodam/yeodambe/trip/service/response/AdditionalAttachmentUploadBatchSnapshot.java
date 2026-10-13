package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.service.DerivedPhotoKeys;

import java.time.LocalDateTime;
import java.util.List;

public record AdditionalAttachmentUploadBatchSnapshot(
        Long batchId,
        String uploadId,
        String executionId,
        Long tripId,
        Long userId,
        String workerToken,
        List<Photo> photos
) {
    public record Photo(
            Long itemId,
            String fileName,
            String contentType,
            long sizeBytes,
            String objectKey,
            int attempts,
            LocalDateTime nextAttemptAt,
            DerivedPhotoKeys derived
    ) {
    }
}
