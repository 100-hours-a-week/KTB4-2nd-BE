package com.yeodam.yeodambe.trip.service.response;

import java.util.List;

public record FavoriteTripListResponse(
        long totalCount,
        List<FavoriteTripListItemResponse> items,
        boolean hasNext,
        String nextCursor
) {
}
