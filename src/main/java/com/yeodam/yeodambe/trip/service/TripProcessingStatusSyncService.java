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
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecutionUpload;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionUploadRepository;

import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TripProcessingStatusSyncService {

    private final TripCreationExecutionRepository creationExecutions;
    private final EntityManager entityManager;
    private final AdditionalAttachmentExecutionRepository additionalExecutions;
    private final ApplicationEventPublisher eventPublisher;
    private final TripAnalysisExecutionRepository analyses;
    private final TripAnalysisExecutionUploadRepository includedUploads;

    @Transactional
    public TripProcessingStateSnapshot synchronizeCreation(
            String executionId,
            long expectedVersion,
            TripDisplayStatus nextStatus,
            String nextStep
    ) {
        TripCreationExecution execution = creationExecutions.findForUpdate(executionId)
                .orElseThrow(TripNotFoundException::new);

        entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);

        boolean changed = execution.getStatusVersion() == expectedVersion
                && execution.updateDisplayStatus(nextStatus, nextStep);

        TripProcessingStateSnapshot snapshot = new TripProcessingStateSnapshot(
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
        AdditionalAttachmentExecution execution = additionalExecutions.findForUpdate(executionId)
                .orElseThrow(TripNotFoundException::new);

        entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);

        boolean changed = execution.getStatusVersion() == expectedVersion
                && execution.updateDisplayStatus(nextStatus, nextStep);

        TripProcessingStateSnapshot snapshot = new TripProcessingStateSnapshot(
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
    public void synchronizeAnalysis(
            String executionId,
            Map<String, Long> expectedVersions,
            TripDisplayStatus nextStatus,
            String nextStep
    ) {
        TripAnalysisExecution execution = analyses.findForUpdate(executionId)
                .orElseThrow(TripNotFoundException::new);
        entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);
        if (execution.getEndedAt() != null || execution.getResultRawPayload() != null
                || execution.getStatus() != TripAnalysisExecution.Status.AI_REQUESTED) {
            return;
        }
        for (TripAnalysisExecutionUpload included :
                includedUploads.findAllByExecutionIdOrderById(executionId)) {
            AdditionalAttachmentExecution addition = additionalExecutions
                    .findForUpdate(included.getAdditionId()).orElseThrow(TripNotFoundException::new);
            entityManager.refresh(addition, LockModeType.PESSIMISTIC_WRITE);
            if (Objects.equals(expectedVersions.get(addition.getExecutionId()), addition.getStatusVersion())
                    && addition.updateDisplayStatus(nextStatus, nextStep)) {
                TripProcessingStateSnapshot snapshot = new TripProcessingStateSnapshot(
                        addition.getTripId(),
                        addition.getExecutionId(),
                        addition.getStatusVersion(),
                        addition.getDisplayStatus(),
                        addition.getCurrentStep(),
                        addition.getErrorCode(),
                        null,
                        executionId,
                        execution.getStatus(),
                        null
                );
                eventPublisher.publishEvent(new TripProcessingStatusChangedEvent(addition.getUserId(), snapshot));
            }
        }
    }
}
