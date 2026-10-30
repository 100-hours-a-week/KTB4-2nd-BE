package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.WritePermissionRequiredException;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.service.response.TripEditAccess;
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
public class TripEditAuthorizationService {
    private static final ZoneId SHARE_ZONE = ZoneId.of("Asia/Seoul");
    private final TripShareUserRepository shareRepository;
    private final Clock clock;

    public void requireWritePermission(Trip trip, Long actorId) {
        TripEditAccess access = requireReadPermission(trip, actorId);
        if (TripSharePermission.READ.name().equals(access.permission())) {
            throw new WritePermissionRequiredException();
        }
    }

    public TripEditAccess requireReadPermission(Trip trip, Long actorId) {
        if (trip.getDeletedAt() != null) {
            throw new TripNotFoundException();
        }
        Instant checkedAt = clock.instant();
        Instant accessValidUntil = checkedAt.plusSeconds(30);
        if (trip.getUserId().equals(actorId)) {
            return new TripEditAccess("OWNER", checkedAt, accessValidUntil);
        }

        LocalDateTime shareCheckedAt = LocalDateTime.ofInstant(checkedAt, SHARE_ZONE);
        TripShareUser share = shareRepository.findByTripIdAndGranteeUserId(trip.getId(), actorId)
                .filter(found -> found.getUserId().equals(trip.getUserId()))
                .filter(found -> found.isEffective(shareCheckedAt))
                .orElseThrow(TripNotFoundException::new);
        Instant shareExpiresAt = share.getExpiresAt().atZone(SHARE_ZONE).toInstant();
        if (shareExpiresAt.isBefore(accessValidUntil)) {
            accessValidUntil = shareExpiresAt;
        }
        return new TripEditAccess(share.getPermission().name(), checkedAt, accessValidUntil);
    }
}
