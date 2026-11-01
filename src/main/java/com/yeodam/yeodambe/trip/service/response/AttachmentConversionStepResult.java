package com.yeodam.yeodambe.trip.service.response;

import java.time.LocalDateTime;

public record AttachmentConversionStepResult(
        Status status,
        LocalDateTime retryAt
) {
    public enum Status {
        CONTINUE,
        RETRY_LATER,
        ACK_READY
    }
}
