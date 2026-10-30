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
import com.yeodam.yeodambe.trip.service.response.TripPhotoDeletionResult;
import com.yeodam.yeodambe.trip.service.response.TripUpdateResponse;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class TripEditCommandTransactionService {
    private final TripRepository trips;
    private final TripEditAuthorizationService authorization;
    private final TripService tripService;
    private final TripAttachmentDeletionService deletionService;
    private final TripEditCommandRecordService records;
    private final TripEditNotificationOutboxService notifications;
    private final EntityManager entityManager;
    private final Clock clock;

    public ApiResponse<TripEditCommandResultResponse> update(
            Long tripId, Long actorId, String requestId, TripUpdateRequest changes, String hash
    ) {
        Trip trip = requireReadableTrip(tripId, actorId);
        Optional<ApiResponse<TripEditCommandResultResponse>> previous = records.findResult(
                actorId, requestId, tripId, CommandType.UPDATE_TRIP, hash, now());
        if (previous.isPresent()) {
            return enqueuePersonalResult(actorId, previous.orElseThrow());
        }
        authorization.requireWritePermission(trip, actorId);
        long previousSequence = trip.getSequence();
        TripUpdateResponse updated = tripService.updateForSharedEditing(trip, changes);
        refresh(trip);
        ApiResponse<TripEditCommandResultResponse> result = success(SuccessMessage.TRIP_UPDATE_SUCCESS, requestId, trip);
        saveResult(actorId, CommandType.UPDATE_TRIP, hash, result);
        if (trip.getSequence() != previousSequence) {
            Map<String, Object> data = eventData(requestId, trip);
            Map<String, Object> changed = new LinkedHashMap<>();
            if (changes.tripName() != null) {
                changed.put("tripName", updated.tripName());
            }
            if (changes.startDate() != null) {
                changed.put("startDate", updated.startDate());
                changed.put("endDate", updated.endDate());
            }
            if (changes.regionCodes() != null) {
                changed.put("regionCodes", updated.regions().stream().map(TripUpdateResponse.Region::regionCode).toList());
                changed.put("regions", updated.regions());
            }
            data.put("changes", changed);
            enqueueTrip(SuccessMessage.TRIP_UPDATED, tripId, data);
        }
        return result;
    }

    public ApiResponse<TripEditCommandResultResponse> deleteAttachments(
            Long tripId, Long actorId, String requestId, List<Long> attachmentIds, String hash
    ) {
        Trip trip = requireReadableTrip(tripId, actorId);
        Optional<ApiResponse<TripEditCommandResultResponse>> previous = records.findResult(
                actorId, requestId, tripId, CommandType.DELETE_ATTACHMENTS, hash, now());
        if (previous.isPresent()) {
            return enqueuePersonalResult(actorId, previous.orElseThrow());
        }
        authorization.requireWritePermission(trip, actorId);
        TripPhotoDeletionResult deleted = deletionService.deleteForSharedEditing(trip, attachmentIds);
        refresh(trip);
        ApiResponse<TripEditCommandResultResponse> result = success(SuccessMessage.TRIP_PHOTOS_DELETE_SUCCESS, requestId, trip);
        saveResult(actorId, CommandType.DELETE_ATTACHMENTS, hash, result);
        Map<String, Object> data = eventData(requestId, trip);
        data.put("attachmentIds", deleted.attachmentIds());
        data.put("attachmentCount", deleted.attachmentCount());
        data.put("thumbnailKey", deleted.thumbnailKey());
        data.put("places", deleted.places());
        enqueueTrip(SuccessMessage.TRIP_PHOTOS_DELETED, tripId, data);
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

    private Trip requireReadableTrip(Long tripId, Long actorId) {
        Trip trip = trips.findActiveForUpdate(tripId).orElseThrow(TripNotFoundException::new);
        authorization.requireReadPermission(trip, actorId);
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

    private Map<String, Object> eventData(String requestId, Trip trip) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventId", UUID.randomUUID());
        data.put("requestId", requestId);
        data.put("tripId", trip.getId());
        data.put("sequence", trip.getSequence());
        data.put("occurredAt", clock.instant());
        return data;
    }

    private void enqueueTrip(SuccessMessage message, Long tripId, Map<String, Object> data) {
        notifications.enqueue(new WebSocketEvent((UUID) data.get("eventId"), WebSocketEventType.TRIP_EVENT,
                1, (Instant) data.get("occurredAt"), new WebSocketEventTarget(WebSocketEventTargetType.TRIP, tripId),
                new ApiResponse<>(message, data)));
    }

    private void refresh(Trip trip) {
        entityManager.flush();
        entityManager.refresh(trip);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
    }
}
