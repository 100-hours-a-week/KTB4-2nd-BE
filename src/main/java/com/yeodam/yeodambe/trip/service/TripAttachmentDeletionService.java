package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentDeleteNotAllowedException;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentIdsException;
import com.yeodam.yeodambe.common.exception.WritePermissionRequiredException;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.response.TripPhotoDeletionResult;
import com.yeodam.yeodambe.user.service.UserStatsService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TripAttachmentDeletionService {
    private final TripRepository tripRepository;
    private final AdditionalAttachmentModificationGuard additionalAttachmentModificationGuard;
    private final EntityManager entityManager;
    private static final int MAX_DELETE_COUNT = 200;

    private static final Comparator<TripAttachment> THUMBNAIL_ORDER = Comparator.comparing(
                    TripAttachment::getEvaluation,
                    Comparator.nullsLast(Comparator.reverseOrder())
            )
            .thenComparing(TripAttachment::getId);

    private final TripAttachmentRepository tripAttachmentRepository;
    private final UserStatsService userStats;
    private final TripMapCacheStore tripMapCacheStore;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteOne(
            Long userId,
            Long tripAttachmentId
    ) {
        TripAttachment attachment = tripAttachmentRepository
                .findAccessibleById(tripAttachmentId, userId)
                .orElseThrow(AttachmentNotFoundException::new);

        softDelete(List.of(attachment), userId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteBulk(
            Long userId,
            List<Long> tripAttachmentIds
    ) {
        validateIds(tripAttachmentIds);

        List<TripAttachment> attachments = tripAttachmentRepository
                .findAllActiveWithTripAndFileByIds(tripAttachmentIds);

        if (attachments.size() != tripAttachmentIds.size()) {
            throw new InvalidAttachmentIdsException();
        }

        boolean containsOtherUsersAttachment = attachments.stream()
                .anyMatch(attachment -> !userId.equals(attachment.getTrip().getUserId()));

        if (containsOtherUsersAttachment) {
            throw new WritePermissionRequiredException();
        }

        softDelete(attachments, userId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TripPhotoDeletionResult deleteForSharedEditing(
            Trip trip,
            List<Long> attachmentIds
    ) {
        validateIds(attachmentIds);
        List<TripAttachment> attachments = tripAttachmentRepository
                .findAllActiveWithTripAndFileByIds(attachmentIds);
        if (attachments.size() != attachmentIds.size()
                || attachments.stream().anyMatch(attachment -> !trip.getId().equals(attachment.getTripId()))) {
            throw new InvalidAttachmentIdsException();
        }

        List<TripDetailPlace> affectedPlaces = attachments.stream()
                .map(TripAttachment::getTripPlace)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<TripAttachment> remaining = softDelete(attachments, trip.getUserId())
                .get(trip.getId());
        Trip updatedTrip = attachments.getFirst().getTrip();
        List<TripPhotoDeletionResult.PlaceChange> places = affectedPlaces.stream()
                .map(place -> new TripPhotoDeletionResult.PlaceChange(
                        place.getId(),
                        place.getDeletedAt() != null,
                        remaining.stream().filter(attachment -> place.getId().equals(attachment.getTripPlaceId())).count(),
                        place.getThumbnailKey()
                ))
                .toList();
        return new TripPhotoDeletionResult(
                List.copyOf(attachmentIds), remaining.size(), updatedTrip.getThumbnailKey(), places);
    }

    private void validateIds(List<Long> tripAttachmentIds) {
        if (tripAttachmentIds == null
                || tripAttachmentIds.isEmpty()
                || tripAttachmentIds.size() > MAX_DELETE_COUNT
                || tripAttachmentIds.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(tripAttachmentIds).size() != tripAttachmentIds.size()) {
            throw new InvalidAttachmentIdsException();
        }
    }

    private Map<Long, List<TripAttachment>> softDelete(List<TripAttachment> attachments, Long userId) {
        attachments.stream().map(TripAttachment::getTripId).distinct().sorted()
                .forEach(tripId -> additionalAttachmentModificationGuard.check(tripId, userId));
        attachments.stream()
                .map(TripAttachment::getTrip)
                .distinct()
                .forEach(trip -> {
                    entityManager.refresh(trip);

                    if (trip.getProcessingStatus() != ProcessingStatus.COMPLETED) {
                        throw new AttachmentDeleteNotAllowedException();
                    }
                });
        attachments.forEach(entityManager::refresh);
        if (attachments.stream().anyMatch(attachment -> attachment.getDeletedAt() != null
                || attachment.getFile().getDeletedAt() != null)) {
            throw new InvalidAttachmentIdsException();
        }
        LocalDateTime deletedAt = LocalDateTime.now();

        List<TripDetailPlace> affectedPlaces = attachments.stream()
                .filter(attachment -> attachment.getTripPlace() != null)
                .map(TripAttachment::getTripPlace)
                .distinct()
                .toList();
        List<Trip> affectedTrips = attachments.stream()
                .filter(attachment -> attachment.getTrip().getThumbnailKey() != null)
                .filter(attachment -> attachment.getTrip().getThumbnailKey()
                        .equals(attachment.getPreviewStorageKey()))
                .map(TripAttachment::getTrip)
                .distinct()
                .toList();

        attachments.forEach(attachment -> {
            attachment.softDelete(deletedAt);
            attachment.getFile().softDelete(deletedAt);
        });

        tripAttachmentRepository.flush();

        Map<Long, List<TripAttachment>> remainingByTrip = new LinkedHashMap<>();
        attachments.stream().map(TripAttachment::getTripId).distinct()
                .forEach(tripId -> remainingByTrip.put(tripId, tripAttachmentRepository
                        .findAllActiveByTripId(tripId, ClassificationStatus.ACTIVE)));
        affectedPlaces.forEach(place -> refreshThumbnail(
                place,
                remainingByTrip.get(place.getTripId()).stream()
                        .filter(attachment -> place.getId().equals(attachment.getTripPlaceId()))
                        .toList(),
                deletedAt
        ));
        affectedTrips.forEach(trip -> trip.changeThumbnailKey(
                thumbnailKey(remainingByTrip.get(trip.getId()))));
        userStats.refreshFromActiveTrips(userId);
        tripMapCacheStore.evictAfterCommit(userId);
        attachments.stream().map(TripAttachment::getTripId).distinct().sorted()
                .forEach(tripRepository::advanceSequence);
        return remainingByTrip;
    }

    private void refreshThumbnail(
            TripDetailPlace place,
            List<TripAttachment> remaining,
            LocalDateTime deletedAt
    ) {
        if (remaining.isEmpty()) {
            place.softDelete(deletedAt);
            return;
        }

        place.changeThumbnailKey(thumbnailKey(remaining));
    }

    private String thumbnailKey(List<TripAttachment> remaining) {
        return remaining.stream()
                .min(THUMBNAIL_ORDER)
                .map(TripAttachment::getPreviewStorageKey)
                .orElse(null);
    }
}
