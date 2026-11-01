package com.yeodam.yeodambe.story.service.response;

import java.util.List;

public record StorySimilarAttachmentListResponse(
        Long sourceTripAttachmentId,
        List<Item> items
) {
    public record Item(
            Long tripAttachmentId,
            double similarityScore
    ) {
    }
}
