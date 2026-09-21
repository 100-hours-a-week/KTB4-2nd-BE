package com.yeodam.yeodambe.trip.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record DerivedPhotoKeys(
        String originalKey,
        String analyzeKey,
        String previewKey,
        OffsetDateTime takenAt,
        BigDecimal latitude,
        BigDecimal longitude,
        String deviceModel
) {
    public DerivedPhotoKeys(String originalKey, String analyzeKey, String previewKey) {
        this(originalKey, analyzeKey, previewKey, null, null, null, null);
    }
}
