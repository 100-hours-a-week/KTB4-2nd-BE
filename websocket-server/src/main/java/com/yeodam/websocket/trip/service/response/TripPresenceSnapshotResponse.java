package com.yeodam.websocket.trip.service.response;

import java.util.List;

public record TripPresenceSnapshotResponse(
        Long tripId,
        String epoch,
        long revision,
        List<Member> members
) {
    public record Member(
            Long userId,
            String nickname,
            String profileImageUrl,
            String permission
    ) {
    }
}
