package com.yeodam.yeodambe.search.service.response;

import java.time.LocalDate;
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
            String tripName,
            LocalDate startDate,
            LocalDate endDate,
            List<String> regionNames,
            long attachmentCount,
            String thumbnailUrl,
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
