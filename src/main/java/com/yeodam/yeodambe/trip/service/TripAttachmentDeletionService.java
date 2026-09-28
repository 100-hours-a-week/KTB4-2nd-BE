package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentIdsException;
import com.yeodam.yeodambe.common.exception.WritePermissionRequiredException;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.user.service.UserStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TripAttachmentDeletionService {
    private static final Comparator<TripAttachment> THUMBNAIL_ORDER = Comparator.comparing(
                    TripAttachment::getEvaluation,
                    Comparator.nullsLast(Comparator.reverseOrder())
            )
            .thenComparing(TripAttachment::getId);

    private final TripAttachmentRepository tripAttachmentRepository;
    private final UserStatsService userStats;

    @Transactional
    public void deleteOne(Long userId, Long tripAttachmentId) {
        TripAttachment attachment = tripAttachmentRepository
                .findAccessibleById(tripAttachmentId, userId)
                .orElseThrow(AttachmentNotFoundException::new);

        softDelete(List.of(attachment), userId);
    }

    @Transactional
    public void deleteBulk(Long userId, List<Long> tripAttachmentIds) {
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

    private void validateIds(List<Long> tripAttachmentIds) {
        if (tripAttachmentIds == null
                || tripAttachmentIds.isEmpty()
                || tripAttachmentIds.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(tripAttachmentIds).size() != tripAttachmentIds.size()) {
            throw new InvalidAttachmentIdsException();
        }
    }

    private void softDelete(List<TripAttachment> attachments, Long userId) {
        LocalDateTime deletedAt = LocalDateTime.now();

        List<TripDetailPlace> affectedPlaces = attachments.stream()
                .filter(attachment -> attachment.getTripPlace() != null)
                .filter(attachment -> attachment.getPreviewStorageKey()
                        .equals(attachment.getTripPlace().getThumbnailKey()))
                .map(TripAttachment::getTripPlace)
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

        affectedPlaces.forEach(this::refreshThumbnail);
        affectedTrips.forEach(this::refreshThumbnail);
        userStats.refreshFromActiveTrips(userId);
    }

    private void refreshThumbnail(TripDetailPlace place) {
        TripAttachment replacement = tripAttachmentRepository
                .findAllActiveByTripPlaceId(place.getId(), ClassificationStatus.ACTIVE)
                .stream()
                .min(THUMBNAIL_ORDER)
                .orElse(null);

        place.changeThumbnailKey(
                replacement == null ? null : replacement.getPreviewStorageKey()
        );
    }

    private void refreshThumbnail(Trip trip) {
        TripAttachment replacement = tripAttachmentRepository
                .findAllActiveByTripId(trip.getId(), ClassificationStatus.ACTIVE)
                .stream()
                .min(THUMBNAIL_ORDER)
                .orElse(null);

        trip.changeThumbnailKey(
                replacement == null ? null : replacement.getPreviewStorageKey()
        );
    }
}
