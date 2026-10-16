package com.yeodam.yeodambe.search.service.response;

import java.util.List;

public record SearchResponse(
        String query,
        String answer,
        String answerError,
        List<Folder> folders,
        List<Attachment> attachments
) {
    public record Folder(
            Long tripId,
            Long tripPlaceId,
            String tripName,
            String placeName,
            String thumbnailUrl,
            long attachmentCount,
            double score
    ) {
    }

    public record Attachment(
            Long tripAttachmentId,
            Long tripId,
            Long tripPlaceId,
            String thumbnailUrl,
            double score
    ) {
    }
}
