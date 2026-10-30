package com.yeodam.websocket.trip.service;

public record TripPermissionChanged(
        long userId,
        long tripId
) {
}
