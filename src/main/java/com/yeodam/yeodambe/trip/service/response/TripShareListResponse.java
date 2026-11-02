package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.entity.TripSharePermission;

import java.time.OffsetDateTime;
import java.util.List;

public record TripShareListResponse(List<Item> items, boolean hasNext, String nextCursor) {
    public record Item(
            Long tripShareId,
            Long granteeUserId,
            String email,
            TripSharePermission permission,
            OffsetDateTime expiresAt
    ) {
    }
}
