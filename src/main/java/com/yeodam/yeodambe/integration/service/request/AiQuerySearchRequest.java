package com.yeodam.yeodambe.integration.service.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yeodam.yeodambe.integration.service.response.AiQueryParseResponse.Intent;

import java.time.OffsetDateTime;
import java.util.List;

public record AiQuerySearchRequest(
        String query,
        Intent intent,
        @JsonProperty("visual_query") String visualQuery,
        List<Candidate> candidates,
        int limit
) {
    public record Candidate(
            @JsonProperty("trip_attachment_id") Long tripAttachmentId,
            @JsonProperty("analyze_storage_key") String analyzeStorageKey,
            @JsonProperty("taken_at") OffsetDateTime takenAt,
            @JsonProperty("place_name") String placeName,
            @JsonProperty("trip_name") String tripName
    ) {
    }
}
