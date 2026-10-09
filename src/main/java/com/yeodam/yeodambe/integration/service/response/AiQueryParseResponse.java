package com.yeodam.yeodambe.integration.service.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;

public record AiQueryParseResponse(
        Intent intent,
        @JsonProperty("date_from") LocalDate dateFrom,
        @JsonProperty("date_to") LocalDate dateTo,
        @JsonProperty("region_names") List<String> regionNames,
        @JsonProperty("visual_query") String visualQuery
) {
    public enum Intent {
        SEARCH,
        QUESTION
    }
}
