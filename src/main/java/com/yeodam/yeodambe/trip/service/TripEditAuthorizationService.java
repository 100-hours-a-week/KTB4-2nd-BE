package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.WritePermissionRequiredException;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository.GrantSnapshot;
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
        return requireReadPermission(trip.getId(), trip.getUserId(), actorId);
    }

    public TripEditAccess requireReadPermission(Long tripId, Long ownerId, Long actorId) {
        Instant checkedAt = clock.instant();
        // 30초 재검증의 처리 여유를 두되 실제 공유 만료는 넘기지 않는다.
        Instant accessValidUntil = checkedAt.plusSeconds(60);
        if (ownerId.equals(actorId)) {
            return new TripEditAccess("OWNER", checkedAt, accessValidUntil);
        }

        LocalDateTime shareCheckedAt = LocalDateTime.ofInstant(checkedAt, SHARE_ZONE);
        GrantSnapshot share = shareRepository.findGrantSnapshot(tripId, actorId)
                .filter(found -> found.getOwnerId().equals(ownerId))
                .filter(found -> found.getDeletedAt() == null && found.getExpiresAt().isAfter(shareCheckedAt))
                .orElseThrow(TripNotFoundException::new);
        Instant shareExpiresAt = share.getExpiresAt().atZone(SHARE_ZONE).toInstant();
        if (shareExpiresAt.isBefore(accessValidUntil)) {
            accessValidUntil = shareExpiresAt;
        }
        return new TripEditAccess(share.getPermission().name(), checkedAt, accessValidUntil);
    }
}
