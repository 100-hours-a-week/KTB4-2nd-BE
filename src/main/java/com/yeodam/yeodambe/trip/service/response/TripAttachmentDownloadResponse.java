package com.yeodam.yeodambe.trip.service.response;

public record TripAttachmentDownloadResponse(
        Long tripAttachmentId,
        String downloadUrl
) {
}