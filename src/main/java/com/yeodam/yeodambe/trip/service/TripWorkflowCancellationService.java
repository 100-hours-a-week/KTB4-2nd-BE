package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;
import com.yeodam.yeodambe.trip.entity.TripPhotoDeletionReservation;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripPhotoDeletionReservationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TripWorkflowCancellationService {
    private final TripAnalysisExecutionRepository analyses;
    private final InitialAttachmentUploadTransactionService initialUploads;
    private final AdditionalAttachmentUploadTransactionService additionalUploads;
    private final TripPhotoDeletionReservationRepository reservations;
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(Trip trip, LocalDateTime now) {
        cancel(trip, now, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelForWithdrawal(Trip trip, LocalDateTime now) {
        cancel(trip, now, true);
    }

    private void cancel(Trip trip, LocalDateTime now, boolean withdrawal) {
        analyses.findActiveForUpdate(trip.getId()).ifPresent(execution -> {
            entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);
            execution.finish(TripAnalysisExecution.Status.CANCELED, null,
                    execution.getProcessOutboxId() != null && execution.getResultRawPayload() == null, now);
        });
        initialUploads.cancelExecution(trip.getId(), trip.getUserId());
        if (withdrawal) {
            additionalUploads.cancelForWithdrawal(trip.getId(), trip.getUserId());
        } else {
            additionalUploads.cancelForDeletion(trip.getId(), trip.getUserId());
        }
        reservations.findAllByTripIdAndStatusOrderById(trip.getId(), TripPhotoDeletionReservation.Status.PENDING)
                .forEach(reservation -> reservation.apply(now));
    }
}
