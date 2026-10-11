package com.yeodam.yeodambe.trip.service.response;

import java.util.List;

public record BulkAttachmentRestoreResponse(
        List<Long> restoredTripAttachmentIds,
        List<Long> tripPlaceIds
) {
}
