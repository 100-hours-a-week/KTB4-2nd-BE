package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentIdsException;
import com.yeodam.yeodambe.common.exception.WritePermissionRequiredException;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.user.service.UserStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class TripAttachmentDeletionService {

    private final TripAttachmentRepository tripAttachmentRepository;
    private final UserStatsService userStats;
    private final TripObjectCleanupService cleanup;

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

        attachments.forEach(attachment -> {
            attachment.softDelete(deletedAt);
            attachment.getFile().softDelete(deletedAt);
        });

        tripAttachmentRepository.flush();

        affectedPlaces.forEach(this::refreshThumbnail);
        userStats.refreshFromActiveTrips(userId);
        cleanupAfterCommit(attachments.stream().map(TripAttachment::getId).toList());
    }

    private void cleanupAfterCommit(List<Long> attachmentIds) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanupSafely(attachmentIds);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanupSafely(attachmentIds);
            }
        });
    }

    private void cleanupSafely(List<Long> attachmentIds) {
        try {
            cleanup.process(attachmentIds);
        } catch (RuntimeException failure) {
            log.warn("삭제된 여행 첨부의 객체 정리에 실패했습니다. attachmentIds={}",
                    attachmentIds, failure);
        }
    }

    private void refreshThumbnail(TripDetailPlace place) {
        TripAttachment replacement = tripAttachmentRepository
                .findAllActiveByTripPlaceId(place.getId(), ClassificationStatus.ACTIVE)
                .stream()
                .min(Comparator
                        .comparing(
                                TripAttachment::getEvaluation,
                                Comparator.nullsLast(Comparator.reverseOrder())
                        )
                        .thenComparing(TripAttachment::getId))
                .orElse(null);

        place.changeThumbnailKey(
                replacement == null ? null : replacement.getPreviewStorageKey()
        );
    }
}
