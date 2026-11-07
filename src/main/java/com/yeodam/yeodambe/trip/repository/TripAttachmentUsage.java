package com.yeodam.yeodambe.trip.repository;

public record TripAttachmentUsage(
        String originalKey,
        Long originalSizeBytes,
        boolean counted
) {
}
