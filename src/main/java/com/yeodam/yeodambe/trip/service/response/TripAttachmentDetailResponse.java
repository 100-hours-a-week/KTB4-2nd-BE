package com.yeodam.yeodambe.trip.service.response;

public record TripAttachmentDetailResponse(
        Long tripAttachmentId,
        String originalUrl,
        boolean pendingDeletion
) {
    public TripAttachmentDetailResponse(Long tripAttachmentId, String originalUrl) {
        this(tripAttachmentId, originalUrl, false);
    }
}
