package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentDeleteNotAllowedException;
import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentIdsException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.WritePermissionRequiredException;
import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEvent;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTarget;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTargetType;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventType;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripPhotoDeletionReservation;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionUploadRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripPhotoDeletionReservationRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.response.TripPhotoDeletionResult;
import com.yeodam.yeodambe.trip.service.response.TripPhotoDeletionResult.PlaceChange;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TripPhotoDeletionReservationService {
    private final TripPhotoDeletionReservationRepository reservations;
    private final TripRepository trips;
    private final TripAttachmentRepository attachments;
    private final AdditionalAttachmentExecutionRepository additions;
    private final AdditionalAttachmentUploadItemRepository items;
    private final TripAnalysisExecutionRepository analyses;
    private final TripAnalysisExecutionUploadRepository includedUploads;
    private final TripAttachmentDeletionService deletion;
    private final TripEditNotificationOutboxService notifications;
    private final EntityManager entityManager;
    private final ObjectMapper json;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteOwned(Long ownerId, String requestId, List<Long> photoIds) {
        deleteOwned(ownerId, requestId, photoIds, false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteOne(Long ownerId, String requestId, Long photoId) {
        deleteOwned(ownerId, requestId, List.of(photoId), true);
    }

    private void deleteOwned(Long ownerId, String requestId, List<Long> photoIds, boolean single) {
        TripAttachmentDeletionService.validateIds(photoIds);
        List<TripAttachment> photos = attachments.findAllUndeletedForDeletion(photoIds);
        if (photos.size() != photoIds.size()) {
            if (single) {
                throw new AttachmentNotFoundException();
            }
            throw new InvalidAttachmentIdsException();
        }
        if (photos.stream().anyMatch(photo -> !ownerId.equals(photo.getTrip().getUserId()))) {
            if (single) {
                throw new AttachmentNotFoundException();
            }
            throw new WritePermissionRequiredException();
        }
        List<Long> tripIds = photos.stream().map(TripAttachment::getTripId).distinct().sorted().toList();
        Map<Long, Trip> locked = new LinkedHashMap<>();
        for (Long tripId : tripIds) {
            locked.put(tripId, lockTrip(tripId));
        }
        for (Long tripId : tripIds) {
            List<Long> tripPhotos = photos.stream().filter(photo -> tripId.equals(photo.getTripId()))
                    .map(TripAttachment::getId).toList();
            deleteForSharedEditing(locked.get(tripId), ownerId, requestId, tripPhotos);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean deleteForSharedEditing(Trip trip, Long actorId, String requestId, List<Long> photoIds) {
        if (hasPendingAnalysis(trip)) {
            reserve(trip.getId(), actorId, requestId, photoIds);
            return true;
        }
        TripPhotoDeletionResult result = deletion.applyReserved(trip, photoIds);
        publishDeleted(trip, requestId, result);
        return false;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> reserve(Long tripId, Long actorId, String requestId, List<Long> photoIds) {
        TripAttachmentDeletionService.validateIds(photoIds);
        Trip trip = lockTrip(tripId);
        List<TripAttachment> photos = attachments.findAllUndeletedForDeletion(photoIds);
        if (photos.size() != photoIds.size() || photos.stream().anyMatch(photo -> !tripId.equals(photo.getTripId()))) {
            throw new InvalidAttachmentIdsException();
        }
        Set<Long> pending = pending(tripId).stream().map(TripPhotoDeletionReservation::getAttachmentId)
                .collect(Collectors.toSet());
        Map<Long, String> photoAdditions = new HashMap<>();
        for (AdditionalAttachmentUploadItem item : items.findAllByTripAttachmentIdIn(photoIds)) {
            photoAdditions.put(item.getTripAttachmentId(), item.getBatch().getAdditionId());
        }
        Set<String> cohort = relatedAdditions(trip);
        List<Long> accepted = new ArrayList<>();
        for (Long photoId : photoIds) {
            if (pending.contains(photoId)) {
                continue;
            }
            String ownAddition = photoAdditions.get(photoId);
            Set<String> related = ownAddition != null && additions.findById(ownAddition)
                    .filter(addition -> !addition.isTerminal()).isPresent() ? Set.of(ownAddition) : cohort;
            reservations.save(new TripPhotoDeletionReservation(tripId, photoId, actorId, requestId,
                    json.writeValueAsString(related)));
            accepted.add(photoId);
        }
        if (!accepted.isEmpty()) {
            trips.advanceSequence(tripId);
            entityManager.refresh(trip);
            Map<String, Object> data = eventData(trip, requestId);
            data.put("attachmentIds", accepted);
            data.put("pendingDeletion", true);
            enqueue(SuccessMessage.TRIP_PHOTOS_DELETE_SCHEDULED, tripId, data);
        }
        return List.copyOf(accepted);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public TripPhotoDeletionResult applyForInput(Long tripId, Set<Long> inputPhotoIds) {
        return apply(lockTrip(tripId), pending(tripId).stream()
                .filter(reservation -> inputPhotoIds.contains(reservation.getAttachmentId())).toList());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void applyFinalFailures(Long tripId, Set<Long> inputPhotoIds) {
        apply(lockTrip(tripId), pending(tripId).stream()
                .filter(reservation -> inputPhotoIds.contains(reservation.getAttachmentId()))
                .filter(this::allRelatedAdditionsEnded).toList());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void settleFailedUpload(String additionId) {
        AdditionalAttachmentExecution addition = additions.findById(additionId).orElseThrow();
        if (settleDeletedTrip(addition.getTripId())) {
            return;
        }
        Set<Long> ownPhotos = items.findAdditionItems(additionId).stream()
                .map(AdditionalAttachmentUploadItem::getTripAttachmentId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        apply(lockTrip(addition.getTripId()), pending(addition.getTripId()).stream()
                .filter(reservation -> ownPhotos.contains(reservation.getAttachmentId())).toList());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void settleEndedAdditions(Long tripId) {
        if (settleDeletedTrip(tripId)) {
            return;
        }
        Trip trip = lockTrip(tripId);
        if (analyses.findActiveForUpdate(tripId).isEmpty()) {
            apply(trip, pending(tripId).stream().filter(this::allRelatedAdditionsEnded).toList());
        }
    }

    private boolean settleDeletedTrip(Long tripId) {
        Trip trip = trips.findAnyForUpdate(tripId).orElseThrow(TripNotFoundException::new);
        entityManager.refresh(trip, LockModeType.PESSIMISTIC_WRITE);
        if (trip.getDeletedAt() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        pending(tripId).forEach(reservation -> reservation.apply(now));
        return true;
    }

    private TripPhotoDeletionResult apply(Trip trip, List<TripPhotoDeletionReservation> ready) {
        Map<DeletionRequest, List<TripPhotoDeletionReservation>> requests = ready.stream()
                .collect(Collectors.groupingBy(
                        reservation -> new DeletionRequest(reservation.getActorId(), reservation.getRequestId()),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        List<TripPhotoDeletionResult> results = new ArrayList<>();
        for (List<TripPhotoDeletionReservation> request : requests.values()) {
            TripPhotoDeletionResult result = applyRequest(trip, request);
            if (result != null) {
                results.add(result);
            }
        }
        if (results.isEmpty()) {
            return null;
        }
        List<Long> deletedIds = results.stream().flatMap(result -> result.attachmentIds().stream()).toList();
        Map<Long, PlaceChange> places = new LinkedHashMap<>();
        for (TripPhotoDeletionResult result : results) {
            for (PlaceChange place : result.places()) {
                places.put(place.tripPlaceId(), place);
            }
        }
        TripPhotoDeletionResult latest = results.getLast();
        return new TripPhotoDeletionResult(deletedIds, latest.attachmentCount(), latest.thumbnailKey(),
                List.copyOf(places.values()));
    }

    private TripPhotoDeletionResult applyRequest(Trip trip, List<TripPhotoDeletionReservation> ready) {
        List<Long> ids = ready.stream().map(TripPhotoDeletionReservation::getAttachmentId).toList();
        List<Long> active = attachments.findAllUndeletedForDeletion(ids).stream().map(TripAttachment::getId).toList();
        TripPhotoDeletionResult result = active.isEmpty() ? null : deletion.applyReserved(trip, active);
        LocalDateTime now = LocalDateTime.now(clock);
        ready.forEach(reservation -> reservation.apply(now));
        if (result != null) {
            publishDeleted(trip, ready.getFirst().getRequestId(), result);
        }
        return result;
    }

    private boolean allRelatedAdditionsEnded(TripPhotoDeletionReservation reservation) {
        Set<String> related = json.readValue(reservation.getRelatedAdditionIds(), new TypeReference<Set<String>>() { });
        return related.stream().allMatch(id -> additions.findById(id)
                .map(AdditionalAttachmentExecution::isTerminal).orElse(false));
    }

    private Set<String> relatedAdditions(Trip trip) {
        TripAnalysisExecution active = analyses.findActiveForUpdate(trip.getId()).orElse(null);
        if (active != null) {
            return includedUploads.findAllByExecutionIdOrderById(active.getExecutionId()).stream()
                    .map(included -> included.getAdditionId()).collect(Collectors.toSet());
        }
        return additions.findByTripIdAndUserIdAndEndedAtIsNull(trip.getId(), trip.getUserId()).stream()
                .map(AdditionalAttachmentExecution::getExecutionId).collect(Collectors.toSet());
    }

    private boolean hasPendingAnalysis(Trip trip) {
        if (trip.getProcessingStatus() != ProcessingStatus.COMPLETED) {
            throw new AttachmentDeleteNotAllowedException();
        }
        return analyses.findActiveForUpdate(trip.getId()).isPresent()
                || !additions.findByTripIdAndUserIdAndEndedAtIsNull(trip.getId(), trip.getUserId()).isEmpty();
    }

    private List<TripPhotoDeletionReservation> pending(Long tripId) {
        return reservations.findAllByTripIdAndStatusOrderById(tripId, TripPhotoDeletionReservation.Status.PENDING);
    }

    private Trip lockTrip(Long tripId) {
        Trip trip = trips.findActiveForUpdate(tripId).orElseThrow(TripNotFoundException::new);
        entityManager.refresh(trip, LockModeType.PESSIMISTIC_WRITE);
        return trip;
    }

    private void publishDeleted(Trip trip, String requestId, TripPhotoDeletionResult result) {
        entityManager.flush();
        entityManager.refresh(trip);
        Map<String, Object> data = eventData(trip, requestId);
        data.put("attachmentIds", result.attachmentIds());
        data.put("attachmentCount", result.attachmentCount());
        data.put("thumbnailKey", result.thumbnailKey());
        data.put("places", result.places());
        enqueue(SuccessMessage.TRIP_PHOTOS_DELETED, trip.getId(), data);
    }

    private Map<String, Object> eventData(Trip trip, String requestId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventId", UUID.randomUUID());
        data.put("requestId", requestId);
        data.put("tripId", trip.getId());
        data.put("sequence", trip.getSequence());
        data.put("occurredAt", clock.instant());
        return data;
    }

    private void enqueue(SuccessMessage message, Long tripId, Map<String, Object> data) {
        notifications.enqueue(new WebSocketEvent((UUID) data.get("eventId"), WebSocketEventType.TRIP_EVENT, 1,
                clock.instant(), new WebSocketEventTarget(WebSocketEventTargetType.TRIP, tripId),
                new ApiResponse<>(message, data)));
    }

    private record DeletionRequest(
            Long actorId,
            String requestId
    ) {
    }
}
