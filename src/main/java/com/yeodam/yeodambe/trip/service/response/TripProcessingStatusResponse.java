package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;

public record TripProcessingStatusResponse(
        Long tripId,
        ProcessingStatus status,
        Progress progress,
        String currentStep,
        Result result,
        Error error
) {
    public record Progress(int done, int total) {
    }

    public record Result(
            Long tripId,
            int placeFolderCount,
            int classifiedAttachmentCount,
            int unclassifiedAttachmentCount
    ) {
    }

    public record Error(String code, String message) {
    }
}
