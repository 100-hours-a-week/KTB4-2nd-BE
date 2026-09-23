package com.yeodam.yeodambe.trip.service.response;

public record TripFavoriteResponse(
        Long tripId,
        boolean isFavorite
) {
}
