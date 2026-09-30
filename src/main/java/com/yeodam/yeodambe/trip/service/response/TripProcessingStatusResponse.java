package com.yeodam.yeodambe.trip.service.response;

public record TripProcessingStatusResponse(
        Long tripId,
        Status status,
        Progress progress,
        String currentStep,
        Result result,
        Error error
) {
    public enum Status {
        PROCESSING, FINALIZING, COMPLETED, FAILED, CANCELED
    }

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
