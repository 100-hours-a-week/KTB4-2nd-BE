package com.yeodam.yeodambe.integration.service.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record PhotosReadyMessage(
        String type,
        @JsonProperty("trip_id") Long tripId,
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("batch_no") Integer batchNo,
        List<Attachment> attachments
) {
    public static PhotosReadyMessage create(
            Long tripId, String executionId, Integer batchNo, List<Attachment> attachments
    ) {
        return new PhotosReadyMessage("photos_ready", tripId, executionId, batchNo, List.copyOf(attachments));
    }

    public record Attachment(
            @JsonProperty("trip_attachment_id") Long tripAttachmentId,
            @JsonProperty("analyze_storage_key") String analyzeStorageKey
    ) {}
}
