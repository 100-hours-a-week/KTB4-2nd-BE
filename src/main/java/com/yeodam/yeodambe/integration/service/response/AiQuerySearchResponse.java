package com.yeodam.yeodambe.integration.service.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record AiQuerySearchResponse(
        List<Attachment> attachments,
        String answer,
        @JsonProperty("answer_error") String answerError
) {
    public record Attachment(
            @JsonProperty("trip_attachment_id") Long tripAttachmentId,
            Double score
    ) {
    }
}
