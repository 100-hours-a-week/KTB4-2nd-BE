package com.yeodam.yeodambe.trip.service.response;

import java.time.LocalDate;
import java.util.List;

public record TripSearchResultResponse(
        List<Attachment> attachments,
        List<Folder> folders
) {
    public record Attachment(
            Long tripAttachmentId,
            Long tripId,
            Long tripPlaceId,
            String thumbnailUrl
    ) {
    }

    public record Folder(
            Long tripId,
            String tripName,
            LocalDate startDate,
            LocalDate endDate,
            List<String> regionNames,
            long attachmentCount,
            String thumbnailUrl
    ) {
    }
}
