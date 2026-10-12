package com.yeodam.yeodambe.story.client.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.util.List;

public record StoryGenerationAiResponse(
        @JsonProperty("trip_id")
        Long tripId,

        @JsonProperty("execution_id")
        String executionId,

        Status status,
        Progress progress,

        @JsonProperty("current_step")
        String currentStep,

        Result result,
        Failure error
) {
    public enum Status {
        QUEUED, PROCESSING, COMPLETED, FAILED, CANCELED
    }

    public record Progress(
            Integer done,
            Integer total
    ) {
    }

    @JsonIgnoreProperties("verification")
    public record Result(
            @JsonProperty("story_summary")
            String storySummary,

            List<Block> blocks
    ) {
    }

    public record Block(
            @JsonProperty("order_number")
            Integer orderNumber,

            @JsonProperty("trip_place_id")
            Long tripPlaceId,

            @JsonProperty("trip_attachment_id")
            Long tripAttachmentId,

            @JsonProperty("day_label")
            String dayLabel,

            @JsonProperty("detail_summary")
            String detailSummary,

            String memo
    ) {
    }

    public record Failure(
            String code,
            String message,
            JsonNode detail
    ) {
    }
}
