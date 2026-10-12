package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.AttachmentRestoreNotAllowedException;
import com.yeodam.yeodambe.common.exception.BulkRestoreFailedException;
import com.yeodam.yeodambe.common.exception.InvalidRestoreRequestException;
import com.yeodam.yeodambe.common.exception.PlaceFolderNotFoundException;
import com.yeodam.yeodambe.common.exception.RestorePlaceFolderRequiredException;
import com.yeodam.yeodambe.common.exception.RestorePlaceMismatchException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.AttachmentIssue;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.request.AttachmentRestoreRequest;
import com.yeodam.yeodambe.trip.service.request.BulkAttachmentRestoreRequest;
import com.yeodam.yeodambe.trip.service.response.AttachmentRestoreResponse;
import com.yeodam.yeodambe.trip.service.response.BulkAttachmentRestoreResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TripAttachmentRestoreService {
    private static final Set<AttachmentIssue> RESTORABLE_ISSUES = Set.of(
            AttachmentIssue.UNCLEAR_LOCATION, AttachmentIssue.BLURRY, AttachmentIssue.DUPLICATED
    );
    private static final Comparator<TripAttachment> THUMBNAIL_ORDER = Comparator.comparing(
            TripAttachment::getEvaluation, Comparator.nullsLast(Comparator.reverseOrder())
    ).thenComparing(TripAttachment::getId);

    private final TripAttachmentRepository tripAttachmentRepository;
    private final TripRepository tripRepository;
    private final TripDetailPlaceRepository tripDetailPlaceRepository;
    private final TripAccessService tripAccessService;
    private final EntityManager entityManager;
    private final Validator validator;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AttachmentRestoreResponse restoreOne(
            Long userId, Long tripAttachmentId, AttachmentRestoreRequest request
    ) {
        validateRequest(request);
        if (tripAttachmentId == null || tripAttachmentId <= 0) {
            throw new InvalidRestoreRequestException();
        }

        TripAttachment attachment = tripAttachmentRepository.findAccessibleById(tripAttachmentId, userId)
                .orElseThrow(AttachmentNotFoundException::new);

        List<BulkAttachmentRestoreRequest.Item> items = List.of(
                new BulkAttachmentRestoreRequest.Item(tripAttachmentId, request.tripPlaceId())
        );

        restore(userId, items, List.of(attachment));

        return new AttachmentRestoreResponse(tripAttachmentId, request.tripPlaceId(), "CLASSIFIED");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BulkAttachmentRestoreResponse restoreBulk(Long userId, BulkAttachmentRestoreRequest request) {
        validateRequest(request);
        List<BulkAttachmentRestoreRequest.Item> items = request.items();
        validateItems(items);

        try {
            List<Long> ids = items.stream()
                    .map(BulkAttachmentRestoreRequest.Item::tripAttachmentId)
                    .toList();
            List<TripAttachment> attachments = tripAttachmentRepository.findAllActiveWithTripAndFileByIds(ids);

            if (attachments.size() != ids.size()) {
                throw new AttachmentNotFoundException();
            }

            restore(userId, items, attachments);
            return new BulkAttachmentRestoreResponse(
                    ids,
                    items.stream()
                            .map(BulkAttachmentRestoreRequest.Item::tripPlaceId)
                            .toList()
            );

        } catch (AttachmentNotFoundException | PlaceFolderNotFoundException
                 | RestorePlaceMismatchException | AttachmentRestoreNotAllowedException exception) {
            throw new BulkRestoreFailedException();
        }
    }

    private void validateRequest(Object request) {
        if (request == null) {
            throw new InvalidRestoreRequestException();
        }

        var violations = validator.validate(request);

        boolean missingDestination = violations.stream()
                .anyMatch(violation ->
                        violation.getPropertyPath().toString().endsWith("tripPlaceId")
                                && violation.getConstraintDescriptor().getAnnotation() instanceof NotNull);

        if (missingDestination) {
            throw new RestorePlaceFolderRequiredException();
        }

        if (!violations.isEmpty()) {
            throw new InvalidRestoreRequestException();
        }
    }

    private void validateItems(List<BulkAttachmentRestoreRequest.Item> items) {
        List<Long> ids = items.stream()
                .map(BulkAttachmentRestoreRequest.Item::tripAttachmentId)
                .toList();

        if (new HashSet<>(ids).size() != ids.size()) {
            throw new InvalidRestoreRequestException();
        }
    }

    private void restore(
            Long userId,
            List<BulkAttachmentRestoreRequest.Item> items,
            List<TripAttachment> attachments
    ) {
        Map<Long, TripAttachment> byId = attachments.stream()
                .collect(Collectors.toMap(
                                TripAttachment::getId, Function.identity()
                        ));

        List<Trip> trips = lockTrips(userId, attachments);
        for (TripAttachment attachment : attachments) {
            entityManager.refresh(attachment);
            entityManager.refresh(attachment.getFile());
            validateRestorableAttachment(attachment);
        }

        Map<Long, Long> destinations = items.stream()
                .collect(Collectors.toMap(
                        BulkAttachmentRestoreRequest.Item::tripAttachmentId,
                        BulkAttachmentRestoreRequest.Item::tripPlaceId
                ));
        for (TripAttachment attachment : attachments) {
            validateMapping(attachment, destinations.get(attachment.getId()));
        }

        List<TripDetailPlace> places = lockDestinations(items);
        Map<Long, TripDetailPlace> placesById = places.stream()
                .collect(Collectors.toMap(
                        TripDetailPlace::getId, Function.identity()
                ));

        for (BulkAttachmentRestoreRequest.Item item : items) {
            validateDestination(byId.get(item.tripAttachmentId()), placesById.get(item.tripPlaceId()));
        }

        restoreAttachments(userId, attachments, destinations);
        refreshThumbnails(places, trips);
    }

    private List<Trip> lockTrips(Long userId, List<TripAttachment> attachments) {
        List<Long> tripIds = attachments.stream()
                .map(TripAttachment::getTripId)
                .distinct()
                .sorted().toList();

        List<Trip> trips = new ArrayList<>();
        for (Long tripId : tripIds) {
            try {
                tripAccessService.requireWritableTrip(tripId, userId);

            } catch (TripNotFoundException exception) {
                throw new AttachmentNotFoundException();
            }

            Trip trip = tripRepository.findOwnedActiveForUpdate(tripId, userId)
                    .orElseThrow(AttachmentNotFoundException::new);

            entityManager.refresh(trip);

            if (trip.getDeletedAt() != null || !userId.equals(trip.getUserId())) {
                throw new AttachmentNotFoundException();
            }

            if (trip.getProcessingStatus() != ProcessingStatus.COMPLETED) {
                throw new AttachmentRestoreNotAllowedException();
            }

            trips.add(trip);
        }
        return trips;
    }

    private List<TripDetailPlace> lockDestinations(List<BulkAttachmentRestoreRequest.Item> items) {
        List<Long> ids = items.stream()
                .map(BulkAttachmentRestoreRequest.Item::tripPlaceId)
                .distinct()
                .sorted().toList();

        List<TripDetailPlace> places = new ArrayList<>();
        for (Long id : ids) {
            TripDetailPlace place = tripDetailPlaceRepository.findByIdForUpdate(id)
                    .orElseThrow(PlaceFolderNotFoundException::new);

            entityManager.refresh(place);

            places.add(place);
        }
        return places;
    }

    private void validateRestorableAttachment(TripAttachment attachment) {
        if (
                attachment.getDeletedAt() != null
                        || attachment.getFile().getDeletedAt() != null
                        || attachment.getTrip().getDeletedAt() != null
        ) {
            throw new AttachmentNotFoundException();
        }

        if (
                attachment.getTrip().getProcessingStatus() != ProcessingStatus.COMPLETED
                        || attachment.getClassificationStatus() != ClassificationStatus.UNCLASSIFIED
                        || !RESTORABLE_ISSUES.contains(attachment.getIssue())
        ) {
            throw new AttachmentRestoreNotAllowedException();
        }
    }

    private void validateMapping(TripAttachment attachment, Long destinationId) {
        if (attachment.getTripPlaceId() != null && !attachment.getTripPlaceId().equals(destinationId)) {
            throw new RestorePlaceMismatchException();
        }
    }

    private void validateDestination(TripAttachment attachment, TripDetailPlace place) {
        if (place.getDeletedAt() != null || !attachment.getTripId().equals(place.getTripId())) {
            throw new PlaceFolderNotFoundException();
        }
    }

    private void restoreAttachments(
            Long userId, List<TripAttachment> attachments, Map<Long, Long> destinations
    ) {
        LocalDateTime updatedAt = LocalDateTime.now();
        List<TripAttachment> ordered = attachments.stream()
                .sorted(Comparator.comparing(TripAttachment::getId))
                .toList();
        for (TripAttachment attachment : ordered) {
            Long destinationId = destinations.get(attachment.getId());

            int updated = tripAttachmentRepository.restoreIfUnclassified(
                    attachment.getId(), attachment.getTripId(), userId,
                    attachment.getTripPlaceId(), destinationId, updatedAt
            );

            entityManager.refresh(attachment);

            if (updated != 1) {
                entityManager.refresh(attachment, LockModeType.PESSIMISTIC_READ);
                entityManager.refresh(attachment.getFile(), LockModeType.PESSIMISTIC_READ);
                entityManager.refresh(attachment.getTrip());

                validateRestorableAttachment(attachment);
                validateMapping(attachment, destinationId);

                throw new AttachmentRestoreNotAllowedException();
            }
        }
    }

    private void refreshThumbnails(List<TripDetailPlace> places, List<Trip> trips) {
        for (TripDetailPlace place : places) {
            TripAttachment representative = tripAttachmentRepository
                    .findAllActiveByTripPlaceId(place.getId(), ClassificationStatus.ACTIVE)
                    .stream()
                    .min(THUMBNAIL_ORDER)
                    .orElse(null);

            place.changeThumbnailKey(
                    representative == null ? null : representative.getPreviewStorageKey()
            );
        }

        for (Trip trip : trips) {
            TripAttachment representative = tripAttachmentRepository
                    .findAllActiveByTripId(trip.getId(), ClassificationStatus.ACTIVE)
                    .stream()
                    .min(THUMBNAIL_ORDER)
                    .orElse(null);
            trip.changeThumbnailKey(
                    representative == null ? null : representative.getPreviewStorageKey()
            );
        }
    }
}
