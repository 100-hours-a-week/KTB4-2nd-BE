package com.yeodam.yeodambe.trip.service.response;

import java.util.List;

public record TripListResponse(
        List<TripListItemResponse> items,
        boolean hasNext,
        String nextCursor
) {
}
