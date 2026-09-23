package com.yeodam.yeodambe.trip.service.request;

import java.util.List;

public record BulkAttachmentDeleteRequest(
        List<Long> tripAttachmentIds
) {
}