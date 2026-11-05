package com.yeodam.yeodambe.trip.service.request;

public record AttachmentPageKey(
        Long tripId,
        Long tripPlaceId,
        String cursor
) {
}
