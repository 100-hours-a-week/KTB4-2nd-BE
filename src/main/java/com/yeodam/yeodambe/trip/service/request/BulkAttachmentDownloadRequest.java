package com.yeodam.yeodambe.trip.service.request;

import java.util.List;

public record BulkAttachmentDownloadRequest(
        List<Long> tripAttachmentIds
) {
}