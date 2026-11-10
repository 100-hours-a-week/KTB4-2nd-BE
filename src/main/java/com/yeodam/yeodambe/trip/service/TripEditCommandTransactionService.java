package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEvent;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTarget;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTargetType;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventType;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripEditCommandRecord.CommandType;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.request.TripUpdateRequest;
import com.yeodam.yeodambe.trip.service.response.TripEditCommandResultResponse;
import com.yeodam.yeodambe.trip.service.response.TripUpdateResponse;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class TripEditCommandTransactionService {
    private final TripRepository trips;
    private final TripService tripService;
    private final TripPhotoDeletionReservationService deletionService;
    private final TripEditCommandRecordService records;
    private final TripEditNotificationOutboxService notifications;
    private final EntityManager entityManager;
    private final Clock clock;

    public ApiResponse<TripEditCommandResultResponse> update(
            Long tripId, Long actorId, String requestId, TripUpdateRequest changes, String hash
    ) {
        Trip trip = requireActiveTrip(tripId);
        Optional<ApiResponse<TripEditCommandResultResponse>> previous = records.findResult(
                actorId, requestId, tripId, CommandType.UPDATE_TRIP, hash, now());
        if (previous.isPresent()) {
            return enqueuePersonalResult(actorId, previous.orElseThrow());
        }
        long previousSequence = trip.getSequence();
        TripUpdateResponse updated = tripService.updateForSharedEditing(trip, changes);
        refresh(trip);
        ApiResponse<TripEditCommandResultResponse> result = success(SuccessMessage.TRIP_UPDATE_SUCCESS, requestId, trip);
        saveResult(actorId, CommandType.UPDATE_TRIP, hash, result);
        if (trip.getSequence() != previousSequence) {
            notifications.enqueueTripUpdated(tripId, trip.getSequence(), requestId, changes, updated);
        }
        return result;
    }

    public ApiResponse<TripEditCommandResultResponse> deleteAttachments(
            Long tripId, Long actorId, String requestId, List<Long> attachmentIds, String hash
    ) {
        Trip trip = requireActiveTrip(tripId);
        Optional<ApiResponse<TripEditCommandResultResponse>> previous = records.findResult(
                actorId, requestId, tripId, CommandType.DELETE_ATTACHMENTS, hash, now());
        if (previous.isPresent()) {
            return enqueuePersonalResult(actorId, previous.orElseThrow());
        }
        boolean scheduled = deletionService.deleteForSharedEditing(trip, actorId, requestId, attachmentIds);
        refresh(trip);
        ApiResponse<TripEditCommandResultResponse> result = success(scheduled
                ? SuccessMessage.TRIP_PHOTOS_DELETE_SCHEDULED : SuccessMessage.TRIP_PHOTOS_DELETE_SUCCESS, requestId, trip);
        saveResult(actorId, CommandType.DELETE_ATTACHMENTS, hash, result);
        return result;
    }

    public ApiResponse<TripEditCommandResultResponse> reject(
            Long tripId, Long actorId, String requestId, CommandType type, String hash, ErrorMessage message
    ) {
        Optional<ApiResponse<TripEditCommandResultResponse>> previous = records.findResult(
                actorId, requestId, tripId, type, hash, now());
        if (previous.isPresent()) {
            return enqueuePersonalResult(actorId, previous.orElseThrow());
        }
        ApiResponse<TripEditCommandResultResponse> result = new ApiResponse<>(message,
                new TripEditCommandResultResponse(requestId, tripId, "REJECTED", null));
        saveResult(actorId, type, hash, result);
        return result;
    }

    public Optional<ApiResponse<TripEditCommandResultResponse>> replayExistingResult(
            Long tripId, Long actorId, String requestId, CommandType type, String hash
    ) {
        return records.findResult(actorId, requestId, tripId, type, hash, now())
                .map(result -> enqueuePersonalResult(actorId, result));
    }

    private Trip requireActiveTrip(Long tripId) {
        Trip trip = trips.findActiveForUpdate(tripId).orElseThrow(TripNotFoundException::new);
        // 권한 조회 때 적재된 엔티티 대신 잠금 획득 후의 여행 상태를 사용한다.
        entityManager.refresh(trip);
        return trip;
    }

    private ApiResponse<TripEditCommandResultResponse> success(SuccessMessage message, String requestId, Trip trip) {
        return new ApiResponse<>(message,
                new TripEditCommandResultResponse(requestId, trip.getId(), "SUCCEEDED", trip.getSequence()));
    }

    private void saveResult(
            Long actorId, CommandType type, String hash, ApiResponse<TripEditCommandResultResponse> result
    ) {
        records.saveResult(actorId, result.data().tripId(), result.data().requestId(), type, hash, result, now());
        enqueuePersonalResult(actorId, result);
    }

    private ApiResponse<TripEditCommandResultResponse> enqueuePersonalResult(Long actorId, ApiResponse<TripEditCommandResultResponse> result) {
        UUID eventId = UUID.nameUUIDFromBytes(("command-result:" + actorId + ":" + result.data().requestId())
                .getBytes(StandardCharsets.UTF_8));
        notifications.enqueue(new WebSocketEvent(eventId, WebSocketEventType.COMMAND_RESULT, 1,
                clock.instant(), new WebSocketEventTarget(WebSocketEventTargetType.USER, actorId), result));
        return result;
    }

    private void refresh(Trip trip) {
        entityManager.flush();
        entityManager.refresh(trip);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
    }
}
