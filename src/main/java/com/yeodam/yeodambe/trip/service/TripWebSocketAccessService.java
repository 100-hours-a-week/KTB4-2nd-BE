package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.service.response.TripPresenceAccessResponse;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.service.ShareUserSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TripWebSocketAccessService {
    private static final ZoneId SHARE_ZONE = ZoneId.of("Asia/Seoul");
    private final TripRepository tripRepository;
    private final TripShareUserRepository shareRepository;
    private final ShareUserSearchService userService;
    private final Clock clock;

    public TripPresenceAccessResponse findPresenceAccess(Long tripId, Long userId) {
        User user = userService.requireActiveUser(userId);
        Trip trip = tripRepository.findByIdAndDeletedAtIsNull(tripId)
                .orElseThrow(TripNotFoundException::new);
        Instant checkedAt = clock.instant();
        if (trip.getUserId().equals(userId)) {
            return response(tripId, userId, user, "OWNER", checkedAt, null);
        }

        TripShareUser share = shareRepository.findByTripIdAndGranteeUserId(tripId, userId)
                .filter(found -> found.getUserId().equals(trip.getUserId()))
                .filter(found -> found.isEffective(LocalDateTime.ofInstant(checkedAt, SHARE_ZONE)))
                .orElseThrow(TripNotFoundException::new);
        Instant shareExpiresAt = share.getExpiresAt().atZone(SHARE_ZONE).toInstant();
        return response(tripId, userId, user, share.getPermission().name(), checkedAt, shareExpiresAt);
    }

    private TripPresenceAccessResponse response(
            Long tripId, Long userId, User user, String permission,
            Instant checkedAt, Instant shareExpiresAt
    ) {
        Instant accessValidUntil = checkedAt.plusSeconds(30);
        if (shareExpiresAt != null && shareExpiresAt.isBefore(accessValidUntil)) {
            accessValidUntil = shareExpiresAt;
        }
        return new TripPresenceAccessResponse(tripId, userId, user.getNickname(),
                user.getProfileImageUrl(), permission, checkedAt, accessValidUntil, shareExpiresAt);
    }
}
