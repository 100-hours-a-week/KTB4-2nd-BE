package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.entity.TripSharePermission;

import java.time.Instant;
import java.util.List;

public record TripReadAccessSnapshot(
        Long tripId,
        Long ownerId,
        List<Member> members
) {

    public TripReadAccessSnapshot {
        members = List.copyOf(members);
    }

    public record Member(
            Long granteeUserId,
            TripSharePermission permission,
            Instant expiresAt
    ) {
    }
}
