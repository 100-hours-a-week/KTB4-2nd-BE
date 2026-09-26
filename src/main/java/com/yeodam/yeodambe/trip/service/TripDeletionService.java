package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripDeletionNotAllowedException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.service.UserStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripDeletionService {
    private final TripRepository trips;
    private final TripRegionRepository regions;
    private final TripDetailPlaceRepository places;
    private final TripAttachmentRepository attachments;
    private final StoredFileRepository files;
    private final UserStatsService userStats;
    private final TripObjectCleanupService cleanup;
    private final TransactionOperations transactions;

    public void delete(Long tripId, Long userId) {
        List<Long> attachmentIds = Objects.requireNonNull(
                transactions.execute(status -> deleteInTransaction(tripId, userId)));
        try {
            cleanup.process(attachmentIds);
        } catch (RuntimeException failure) {
            log.warn("삭제된 여행의 객체 정리에 실패했습니다. tripId={}", tripId, failure);
        }
    }

    private List<Long> deleteInTransaction(Long tripId, Long userId) {
        Trip trip = trips.findOwnedActiveForUpdate(tripId, userId)
                .orElseThrow(TripNotFoundException::new);

        if (trip.getProcessingStatus() == ProcessingStatus.PROCESSING) {
            throw new TripDeletionNotAllowedException();
        }

        LocalDateTime deletedAt = LocalDateTime.now();
        List<TripAttachment> tripAttachments =
                attachments.findAllByTripIdAndDeletedAtIsNull(tripId);
        List<Long> fileIds = tripAttachments.stream()
                .map(TripAttachment::getFileId)
                .distinct()
                .toList();

        regions.softDeleteByTripId(tripId, deletedAt);
        places.softDeleteByTripId(tripId, deletedAt);
        attachments.softDeleteByTripId(tripId, deletedAt);
        trip.softDelete(deletedAt);

        List<Long> unreferencedFileIds = fileIds.stream()
                .filter(fileId -> !attachments.existsByFileIdAndDeletedAtIsNull(fileId))
                .toList();
        if (!unreferencedFileIds.isEmpty()) files.softDeleteByIds(unreferencedFileIds, deletedAt);

        userStats.refreshFromActiveTrips(userId);
        return tripAttachments.stream()
                .map(TripAttachment::getId)
                .toList();
    }
}
