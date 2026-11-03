package com.yeodam.yeodambe.story.client.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yeodam.yeodambe.story.entity.Story;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record StoryTonePreviewAiRequest(
        @JsonProperty("trip_name")
        String tripName,

        Period period,

        Story.Mood mood,

        List<Block> blocks
) {
    public record Period(
            @JsonProperty("start_date")
            LocalDate startDate,

            @JsonProperty("end_date")
            LocalDate endDate
    ) {
    }

    public record Block(
            @JsonProperty("order_number")
            Integer orderNumber,

            @JsonProperty("trip_attachment_id")
            Long tripAttachmentId,

            @JsonProperty("place_name")
            String placeName,

            @JsonProperty("taken_at")
            OffsetDateTime takenAt,

            @JsonProperty("analyze_storage_key")
            String analyzeStorageKey,

            @JsonProperty("day_label")
            String dayLabel,

            @JsonProperty("detail_summary")
            String detailSummary,

            String memo
    ) {
    }
}