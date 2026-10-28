package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.entity.TripSharePermission;

import java.time.OffsetDateTime;

public record TripShareCreateResponse(
        Long tripShareId,
        Long granteeUserId,
        TripSharePermission permission,
        OffsetDateTime expiresAt
) {
}
