package com.yeodam.yeodambe.story.client.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record StorySimilarAttachmentAiRequest(
        @JsonProperty("trip_attachment_id")
        Long tripAttachmentId,

        @JsonProperty("candidate_attachment_ids")
        List<Long> candidateAttachmentIds,

        Integer limit
) {
}
