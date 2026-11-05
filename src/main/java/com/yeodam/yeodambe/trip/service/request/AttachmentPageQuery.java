package com.yeodam.yeodambe.trip.service.request;

public record AttachmentPageQuery(
        Long userId,
        Long tripId,
        Long tripPlaceId,
        String cursor
) {
}
