package com.yeodam.yeodambe.trip.service.request;

public record AttachmentConversionMessage(
        int schemaVersion,
        String taskId
) {
}
