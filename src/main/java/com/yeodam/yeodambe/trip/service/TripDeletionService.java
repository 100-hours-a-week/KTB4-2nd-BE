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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TripDeletionService {
    private final TripRepository trips;
    private final TripRegionRepository regions;
    private final TripDetailPlaceRepository places;
    private final TripAttachmentRepository attachments;
    private final StoredFileRepository files;
    private final UserStatsService userStats;
    private final TransactionOperations transactions;

    public void delete(Long tripId, Long userId) {
        transactions.executeWithoutResult(status -> deleteInTransaction(tripId, userId));
    }

    private void deleteInTransaction(Long tripId, Long userId) {
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
    }
}
