package com.yeodam.yeodambe.trip.service.request;

import java.util.List;

public record InitialAttachmentUploadUrlRequest(
        Integer batchNo,
        Integer totalAttachmentCount,
        Boolean complete,
        List<Attachment> attachments
) {
    public record Attachment(
            String fileName,
            String contentType,
            Long sizeBytes
    ) {
    }
}