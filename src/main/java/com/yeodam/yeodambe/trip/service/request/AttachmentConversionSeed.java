package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;

import java.util.List;

public record AttachmentConversionSeed(
        Kind kind,
        Long batchId,
        Long tripId,
        String requestId,
        List<Photo> photos
) {
    public record Photo(
            Long itemId,
            String contentType,
            long sizeBytes
    ) {
    }
}
