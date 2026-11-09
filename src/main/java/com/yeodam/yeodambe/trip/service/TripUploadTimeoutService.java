package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.trip.result-worker-enabled", havingValue = "true", matchIfMissing = true)
public class TripUploadTimeoutService {
    private final TripCreationExecutionRepository creationExecutions;
    private final TripRepository trips;
    private final InitialAttachmentUploadTransactionService uploadTransactions;
    private final TripPhotoAnalysisService analysis;

    @Scheduled(initialDelayString = "${app.trip.worker-initial-delay-ms:0}", fixedDelay = 300_000)
    void failExpiredUploads() {
        LocalDateTime now = uploadTransactions.databaseTime();
        PageRequest page = PageRequest.of(0, 100);
        creationExecutions.findUploadExpired(now.minusMinutes(10), page)
                .forEach(execution -> uploadTransactions.failExpiredExecution(execution.getExecutionId(), false));
        creationExecutions.findResultExpired(now.minusHours(1), page)
                .forEach(execution -> uploadTransactions.failExpiredExecution(execution.getExecutionId(), true));
        failUnstartedTrips(now, page);
        for (TripCreationExecution execution : creationExecutions.findByCancelPendingTrue(page)) {
            try {
                analysis.cancel(execution.getTripId());
                uploadTransactions.recordCancellationDelivered(execution.getExecutionId());
            } catch (RuntimeException failure) {
                log.warn(TripInternalErrorMessage.AI_CANCELLATION_DELIVERY_RETRY.message(), execution.getExecutionId(),
                        failure.getClass().getSimpleName());
            }
        }
    }

    private void failUnstartedTrips(LocalDateTime now, PageRequest page) {
        for (Long tripId : trips.findUnstartedUploadExpired(now.minusMinutes(10), page)) {
            try {
                uploadTransactions.failUnstartedTrip(tripId);
            } catch (RuntimeException failure) {
                log.warn(TripInternalErrorMessage.UNSTARTED_UPLOAD_EXPIRATION_RETRY.message(),
                        tripId, failure.getClass().getSimpleName());
            }
        }
    }
}
