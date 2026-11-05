package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;

import java.util.Objects;

public record CachedAttachmentResponse(
        TripAttachmentListResponse response,
        long validUntilNanos,
        String refreshId,
        long generation
) {

    public CachedAttachmentResponse {
        Objects.requireNonNull(response, TripInternalErrorMessage.PHOTO_LIST_RESPONSE_MISSING.message());
        Objects.requireNonNull(refreshId, TripInternalErrorMessage.PHOTO_LIST_RESPONSE_REFRESH_ID_MISSING.message());
    }

    public boolean isUsableAt(long nowNanos) {
        return validUntilNanos - nowNanos > 0;
    }
}
