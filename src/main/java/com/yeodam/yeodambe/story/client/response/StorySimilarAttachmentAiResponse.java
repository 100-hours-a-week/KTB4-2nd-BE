package com.yeodam.yeodambe.story.client.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record StorySimilarAttachmentAiResponse(
        @JsonProperty("source_attachment_id")
        Long sourceAttachmentId,

        List<Item> items
) {
    public record Item(
            @JsonProperty("trip_attachment_id")
            Long tripAttachmentId,

            Double score
    ) {
    }
}
