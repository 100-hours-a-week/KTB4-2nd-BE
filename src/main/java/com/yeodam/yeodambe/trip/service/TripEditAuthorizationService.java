package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.WritePermissionRequiredException;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
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
        if (trip.getDeletedAt() != null) {
            throw new TripNotFoundException();
        }
        if (trip.getUserId().equals(actorId)) {
            return;
        }

        LocalDateTime checkedAt = LocalDateTime.ofInstant(clock.instant(), SHARE_ZONE);
        TripShareUser share = shareRepository.findByTripIdAndGranteeUserId(trip.getId(), actorId)
                .filter(found -> found.getUserId().equals(trip.getUserId()))
                .filter(found -> found.isEffective(checkedAt))
                .orElseThrow(TripNotFoundException::new);
        if (share.getPermission() != TripSharePermission.WRITE) {
            throw new WritePermissionRequiredException();
        }
    }
}
