package com.yeodam.yeodambe.trip.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record DerivedPhotoKeys(
        String originalKey,
        String analyzeKey,
        String previewKey,
        String displayKey,
        OffsetDateTime takenAt,
        BigDecimal latitude,
        BigDecimal longitude,
        String deviceModel,
        Long originalSizeBytes,
        Long analyzeSizeBytes,
        Long previewSizeBytes,
        Long displaySizeBytes
) {
    public DerivedPhotoKeys(String originalKey, String analyzeKey, String previewKey) {
        this(originalKey, analyzeKey, previewKey, null, null, null, null, null);
    }

    public DerivedPhotoKeys(
            String originalKey,
            String analyzeKey,
            String previewKey,
            String displayKey,
            OffsetDateTime takenAt,
            BigDecimal latitude,
            BigDecimal longitude,
            String deviceModel
    ) {
        this(originalKey, analyzeKey, previewKey, displayKey,
                takenAt, latitude, longitude, deviceModel,
                null, null, null, null);
    }

    public DerivedPhotoKeys(
            String originalKey,
            String analyzeKey,
            String previewKey,
            OffsetDateTime takenAt,
            BigDecimal latitude,
            BigDecimal longitude,
            String deviceModel
    ) {
        this(originalKey, analyzeKey, previewKey, null,
                takenAt, latitude, longitude, deviceModel);
    }
}
