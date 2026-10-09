package com.yeodam.yeodambe.integration.service.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yeodam.yeodambe.integration.service.response.AiQueryParseResponse.Intent;
import java.util.List;

public record AiQuerySearchRequest(
        Intent intent,
        @JsonProperty("visual_query") String visualQuery,
        @JsonProperty("candidate_attachment_ids") List<Long> candidateAttachmentIds,
        int limit
) {
}
