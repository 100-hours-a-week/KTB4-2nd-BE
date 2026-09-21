package com.yeodam.yeodambe.integration.service.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record TripPhotoAnalysisRequest(
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("trip_name") String tripName,
        Period period,
        List<Region> regions,
        List<Photo> attachments
) {
    public record Period(
            @JsonProperty("start_date") LocalDate startDate,
            @JsonProperty("end_date") LocalDate endDate
    ) {
}
    public record Region(BigDecimal latitude, BigDecimal longitude) {
    }

    public record Photo(
            @JsonProperty("trip_attachment_id") Long tripAttachmentId,
            @JsonProperty("analyze_storage_key") String analyzeStorageKey,
            @JsonProperty("taken_at") OffsetDateTime takenAt,
            BigDecimal latitude,
            BigDecimal longitude,
            @JsonProperty("device_model") String deviceModel
            ) {
    }

}
