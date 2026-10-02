package com.yeodam.yeodambe.trip.repository;

public record TripStorageObjectKeys(
        String originalKey,
        String analyzeKey,
        String previewKey,
        String displayKey,
        Long originalSizeBytes,
        Long analyzeSizeBytes,
        Long previewSizeBytes,
        Long displaySizeBytes
) {
    public TripStorageObjectKeys(
            String originalKey,
            String analyzeKey,
            String previewKey,
            String displayKey
    ) {
        this(originalKey, analyzeKey, previewKey, displayKey,
                null, null, null, null);
    }
}
