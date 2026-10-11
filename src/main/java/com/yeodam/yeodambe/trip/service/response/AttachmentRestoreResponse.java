package com.yeodam.yeodambe.trip.service.response;

public record AttachmentRestoreResponse(
        Long tripAttachmentId,
        Long tripPlaceId,
        String classificationType
) {
}
