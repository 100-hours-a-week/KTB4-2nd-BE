package com.yeodam.yeodambe.integration.service.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

public record AiQueryParseRequest(
        String query,
        @JsonProperty("reference_date") LocalDate referenceDate
) {
}
