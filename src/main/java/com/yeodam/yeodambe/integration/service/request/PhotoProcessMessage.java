package com.yeodam.yeodambe.integration.service.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record PhotoProcessMessage(
        String type,
        @JsonProperty("trip_id") Long tripId,
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("trip_name") String tripName,
        TripPhotoAnalysisRequest.Period period,
        List<TripPhotoAnalysisRequest.Region> regions,
        List<TripPhotoAnalysisRequest.Photo> attachments
) {
    public static PhotoProcessMessage from(
            Long tripId, String executionId, TripPhotoAnalysisRequest request
    ) {
        return new PhotoProcessMessage("process", tripId, executionId, request.tripName(),
                request.period(), List.copyOf(request.regions()), List.copyOf(request.attachments()));
    }
}
