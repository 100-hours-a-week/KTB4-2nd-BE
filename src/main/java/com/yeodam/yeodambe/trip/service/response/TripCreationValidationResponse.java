package com.yeodam.yeodambe.trip.service.response;

public record TripCreationValidationResponse(
        boolean canCreate,
        String reasonCode
) {
}
