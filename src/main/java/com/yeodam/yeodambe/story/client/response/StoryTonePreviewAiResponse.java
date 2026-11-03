package com.yeodam.yeodambe.story.client.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record StoryTonePreviewAiResponse(
        @JsonProperty("story_summary")
        String storySummary,

        List<Block> blocks
) {
    public record Block(
            @JsonProperty("order_number")
            Integer orderNumber,

            @JsonProperty("trip_attachment_id")
            Long tripAttachmentId,

            @JsonProperty("day_label")
            String dayLabel,

            @JsonProperty("detail_summary")
            String detailSummary,

            String memo
    ) {
    }
}