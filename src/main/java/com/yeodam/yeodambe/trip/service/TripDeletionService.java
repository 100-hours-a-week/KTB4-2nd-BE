package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.service.UserStatsService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TripDeletionService {
    private final TripEditLock editLock;
    private final TripWorkflowCancellationService cancellations;
    private final EntityManager entityManager;
    private final TripRepository trips;
    private final TripRegionRepository regions;
    private final TripDetailPlaceRepository places;
    private final TripAttachmentRepository attachments;
    private final StoredFileRepository files;
    private final UserStatsService userStats;
    private final PlatformTransactionManager transactionManager;
    private final MeterRegistry meterRegistry;
    private final TripMapCacheStore tripMapCacheStore;

    public void delete(
            Long tripId,
            Long userId
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            trips.findByIdAndUserIdAndDeletedAtIsNull(tripId, userId)
                    .orElseThrow(TripNotFoundException::new);
            editLock.execute(tripId, () -> {
                transactions.executeWithoutResult(status -> deleteInTransaction(tripId, userId));
                return null;
            });
        } catch (RuntimeException failure) {
            outcome = "failure";
            throw failure;
        } finally {
            sample.stop(Timer.builder("yeodam.trip.stage")
                    .tags("stage", "trip_delete", "outcome", outcome)
                    .register(meterRegistry));
        }
    }

    private void deleteInTransaction(
            Long tripId,
            Long userId
    ) {
        Trip trip = trips.findOwnedActiveForUpdate(tripId, userId)
                .orElseThrow(TripNotFoundException::new);

        entityManager.refresh(trip, LockModeType.PESSIMISTIC_WRITE);
        LocalDateTime deletedAt = LocalDateTime.now();
        cancellations.cancel(trip, deletedAt);
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
        tripMapCacheStore.evictAfterCommit(userId);
        trips.advanceSequence(tripId);
    }
}
