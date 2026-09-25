package com.yeodam.yeodambe.trip.service.response;

public record BulkAttachmentDownloadResponse(
        String fileName,
        String downloadUrl
) {
}
