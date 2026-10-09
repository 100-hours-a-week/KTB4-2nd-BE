package com.yeodam.yeodambe.integration.service.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiQueryParseRequest(
        String query,
        @JsonProperty("trip_id") Long tripId
) {
}
