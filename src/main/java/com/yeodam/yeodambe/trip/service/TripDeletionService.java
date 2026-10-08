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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

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
    private final MeterRegistry meterRegistry;

    public void delete(Long tripId, Long userId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";

        try {
            transactions.executeWithoutResult(status -> deleteInTransaction(tripId, userId));
        } catch (RuntimeException failure) {
            outcome = "failure";
            throw failure;
        } finally {
            sample.stop(Timer.builder("yeodam.trip.stage")
                    .tags("stage", "trip_delete", "outcome", outcome)
                    .register(meterRegistry));
        }
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

        if (!fileIds.isEmpty()) {
            files.softDeleteByIds(fileIds, deletedAt);
        }

        userStats.refreshFromActiveTrips(userId);
    }
}
