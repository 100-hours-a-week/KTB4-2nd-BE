package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidTripShareRequestException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.TripShareAlreadyExistsException;
import com.yeodam.yeodambe.common.exception.TripShareCreateForbiddenException;
import com.yeodam.yeodambe.common.exception.TripShareNotAvailableException;
import com.yeodam.yeodambe.common.exception.UserNotFoundException;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.service.request.TripShareCreateRequest;
import com.yeodam.yeodambe.trip.service.request.cursor.TripShareCursor;
import com.yeodam.yeodambe.trip.service.response.TripShareCreateResponse;
import com.yeodam.yeodambe.trip.service.response.TripShareListResponse;
import com.yeodam.yeodambe.user.service.ShareUserSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ShareTripService {
    private static final int PAGE_SIZE = 5;
    private static final ZoneId SHARE_ZONE = ZoneId.of("Asia/Seoul");
    private final TripRepository tripRepository;
    private final TripShareUserRepository shareRepository;
    private final ShareUserSearchService userSearchService;
    private final Clock clock;
    private final TripAccessService tripAccessService;
    private final ObjectMapper objectMapper;
    private final TripMutationCoordinator mutations;

    @Transactional(readOnly = true)
    public TripShareListResponse findShares(Long tripId, Long requesterId, String cursor) {
        Trip trip = tripAccessService.requireOwnedTrip(tripId, requesterId);
        TripShareCursor decoded = TripShareCursor.decode(cursor, tripId, objectMapper);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), SHARE_ZONE);
        List<TripShareUser> fetched = shareRepository.findActiveSharesWithGrantee(
                trip.getId(), trip.getUserId(), now,
                decoded == null ? null : decoded.lastTripShareId(), PageRequest.of(0, PAGE_SIZE + 1));
        boolean hasNext = fetched.size() > PAGE_SIZE;
        List<TripShareUser> page = hasNext ? fetched.subList(0, PAGE_SIZE) : fetched;
        List<TripShareListResponse.Item> items = page.stream().map(this::toShareItem).toList();
        String nextCursor = hasNext
                ? new TripShareCursor(tripId, page.getLast().getId()).encode(objectMapper)
                : null;
        return new TripShareListResponse(items, hasNext, nextCursor);
    }

    private TripShareListResponse.Item toShareItem(TripShareUser share) {
        return new TripShareListResponse.Item(
                share.getId(), share.getGranteeUserId(), share.getGrantee().getEmail(), share.getPermission(),
                share.getExpiresAt().atZone(SHARE_ZONE).toOffsetDateTime());
    }

    public TripShareCreateResponse register(Long tripId, Long ownerId, TripShareCreateRequest request) {
        Trip trip = tripRepository.findByIdAndDeletedAtIsNull(tripId)
                .orElseThrow(TripNotFoundException::new);
        validateTrip(trip, ownerId);
        return mutations.execute(tripId, () -> registerInTransaction(tripId, ownerId, request));
    }

    private TripShareCreateResponse registerInTransaction(
            Long tripId, Long ownerId, TripShareCreateRequest request
    ) {
        Trip trip = tripRepository.findActiveForUpdate(tripId)
                .orElseThrow(TripNotFoundException::new);
        validateTrip(trip, ownerId);
        validateGrantee(ownerId, request.granteeUserId());

        Optional<TripShareUser> existing = shareRepository.findByTripIdAndGranteeUserId(
                tripId, request.granteeUserId());

        Instant registeredAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDateTime now = LocalDateTime.ofInstant(registeredAt, SHARE_ZONE);
        LocalDateTime expiresAt = LocalDateTime.ofInstant(registeredAt.plus(24, ChronoUnit.HOURS), SHARE_ZONE);

        TripShareUser share;
        if (existing.isPresent()) {
            share = existing.get();

            if (share.isEffective(now)) {
                throw new TripShareAlreadyExistsException();
            }

            share.reactivate(request.permission(), expiresAt, now);

        } else {
            share = shareRepository.save(new TripShareUser(
                    tripId, ownerId, request.granteeUserId(), request.permission(), expiresAt, now));
        }

        return new TripShareCreateResponse(
                share.getId(), share.getGranteeUserId(), share.getPermission(),
                share.getExpiresAt().atZone(SHARE_ZONE).toOffsetDateTime());
    }

    private void validateTrip(Trip trip, Long ownerId) {
        if (!trip.getUserId().equals(ownerId)) {
            throw new TripShareCreateForbiddenException();
        }

        if (trip.getProcessingStatus() != ProcessingStatus.COMPLETED) {
            throw new TripShareNotAvailableException();
        }
    }

    private void validateGrantee(Long ownerId, Long granteeUserId) {
        try {
            userSearchService.requireActiveUser(granteeUserId);

        } catch (UserNotFoundException exception) {
            throw new InvalidTripShareRequestException();
        }

        if (ownerId.equals(granteeUserId)) {
            throw new InvalidTripShareRequestException();
        }
    }
}
