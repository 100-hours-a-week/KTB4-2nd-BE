package com.yeodam.yeodambe.trip.service.response;

import java.time.LocalDateTime;

public record AttachmentConversionAttemptResult(
        Status status,
        AttachmentConversionClaim.Attempt attempt,
        LocalDateTime retryAt
) {
    public enum Status {
        STARTED,
        ALREADY_SAVED,
        RETRY_LATER,
        ACK_READY
    }
}
