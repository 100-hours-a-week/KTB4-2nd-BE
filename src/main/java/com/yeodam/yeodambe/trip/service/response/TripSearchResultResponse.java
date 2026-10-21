package com.yeodam.yeodambe.trip.service.response;

import java.util.List;

public record TripSearchResultResponse(
        List<Attachment> attachments,
        List<Folder> folders
) {
    public record Attachment(
            Long tripAttachmentId,
            Long tripId,
            String tripName,
            Long tripPlaceId,
            String thumbnailUrl
    ) {
    }

    public record Folder(
            Long tripId,
            Long tripPlaceId,
            String tripName,
            String placeName,
            String thumbnailUrl,
            long attachmentCount
    ) {
    }
}
