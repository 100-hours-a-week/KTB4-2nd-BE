package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.event.TripProcessingStatusChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.TripDisplayStatus;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStateSnapshot;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TripProcessingStatusSyncService {

    private final TripCreationExecutionRepository creationExecutions;
    private final EntityManager entityManager;
    private final AdditionalAttachmentExecutionRepository additionalExecutions;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public TripProcessingStateSnapshot synchronizeCreation(
            String executionId,
            long expectedVersion,
            TripDisplayStatus nextStatus,
            String nextStep
    ) {
        var execution = creationExecutions.findForUpdate(executionId)
                .orElseThrow(TripNotFoundException::new);

        entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);

        boolean changed = execution.getStatusVersion() == expectedVersion
                && execution.updateDisplayStatus(nextStatus, nextStep);

        var snapshot = new TripProcessingStateSnapshot(
                execution.getTripId(),
                execution.getExecutionId(),
                execution.getStatusVersion(),
                execution.getDisplayStatus(),
                execution.getCurrentStep(),
                execution.getErrorCode(),
                execution.getDisplayStatus() == TripDisplayStatus.COMPLETED ? execution.getResultRawPayload() : null
        );

        if (changed) {
            eventPublisher.publishEvent(new TripProcessingStatusChangedEvent(
                    execution.getUserId(), snapshot));
        }

        return snapshot;
    }
    @Transactional
    public TripProcessingStateSnapshot synchronizeAddition(
            String executionId,
            long expectedVersion,
            TripDisplayStatus nextStatus,
            String nextStep
    ) {
        var execution = additionalExecutions.findForUpdate(executionId)
                .orElseThrow(TripNotFoundException::new);

        entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);

        boolean changed = execution.getStatusVersion() == expectedVersion
                && execution.updateDisplayStatus(nextStatus, nextStep);

        var snapshot = new TripProcessingStateSnapshot(
                execution.getTripId(),
                execution.getExecutionId(),
                execution.getStatusVersion(),
                execution.getDisplayStatus(),
                execution.getCurrentStep(),
                execution.getErrorCode(),
                execution.getDisplayStatus() == TripDisplayStatus.COMPLETED ? execution.getResultRawPayload() : null
        );

        if (changed) {
            eventPublisher.publishEvent(new TripProcessingStatusChangedEvent(
                    execution.getUserId(), snapshot));
        }

        return snapshot;
    }
}
