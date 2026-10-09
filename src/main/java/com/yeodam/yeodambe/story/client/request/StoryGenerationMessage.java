package com.yeodam.yeodambe.story.client.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yeodam.yeodambe.story.entity.Story;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record StoryGenerationMessage(
        @JsonProperty("trip_id")
        Long tripId,

        @JsonProperty("execution_id")
        String executionId,

        @JsonProperty("trip_name")
        String tripName,

        Period period,
        Story.Mood mood,
        List<Place> places
) {
    @JsonProperty("type")
    public String type() {
        return "story";
    }

    public record Period(
            @JsonProperty("start_date")
            LocalDate startDate,

            @JsonProperty("end_date")
            LocalDate endDate
    ) {
    }

    public record Place(
            @JsonProperty("trip_place_id")
            Long tripPlaceId,

            @JsonProperty("place_name")
            String placeName,

            List<Attachment> attachments
    ) {
    }

    public record Attachment(
            @JsonProperty("trip_attachment_id")
            Long tripAttachmentId,

            @JsonProperty("analyze_storage_key")
            String analyzeStorageKey,

            @JsonProperty("taken_at")
            OffsetDateTime takenAt,

            Integer evaluation
    ) {
    }
}
