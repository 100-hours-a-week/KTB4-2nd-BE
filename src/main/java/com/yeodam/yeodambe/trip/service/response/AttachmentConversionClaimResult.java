package com.yeodam.yeodambe.trip.service.response;

import java.time.LocalDateTime;

public record AttachmentConversionClaimResult(
        Status status,
        AttachmentConversionClaim claim,
        LocalDateTime retryAt
) {
    public enum Status {
        CLAIMED,
        RETRY_LATER,
        ACK_TERMINAL
    }
}
