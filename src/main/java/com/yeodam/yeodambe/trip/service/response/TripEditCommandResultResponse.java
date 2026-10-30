package com.yeodam.yeodambe.trip.service.response;

public record TripEditCommandResultResponse(
        String requestId,
        Long tripId,
        String status,
        Long sequence
) {
}
