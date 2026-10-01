package com.yeodam.yeodambe.trip.service.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record InitialAttachmentUploadUrlResponse(
        String uploadId,
        List<Attachment> attachments
) {
    public record Attachment(
            String fileName,
            String uploadUrl,
            String method,
            Map<String, String> headers,
            OffsetDateTime expiresAt
    ) {
    }
}