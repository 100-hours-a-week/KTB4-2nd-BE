package com.yeodam.yeodambe.trip.service.response;

import java.time.LocalDateTime;

public record AttachmentConversionRecoveryCandidate(
        String taskId,
        LocalDateTime createdAt,
        long latestDeliveryNo,
        long publicationVersion,
        AttachmentConversionPublication publication
) {
    public record Cursor(
            LocalDateTime createdAt,
            String taskId
    ) {
    }
}
