package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStateSnapshot;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.integration.service.response.TripPhotoAnalysisStatusResponse;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.TripDisplayStatus;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse.Status;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadOutboxRepository;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionUploadRepository;
import com.yeodam.yeodambe.trip.service.response.AdditionalAttachmentProcessingStatusResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TripProcessingStatusService {
    private final TripRepository trips;
    private final TripDetailPlaceRepository places;
    private final TripAttachmentRepository attachments;
    private final TripPhotoAnalysisService analysis;
    private final InitialAttachmentUploadBatchRepository uploadBatches;
    private final TripCreationExecutionRepository creationExecutions;
    private final AdditionalAttachmentExecutionRepository additionalExecutions;
    private final TripProcessingStatusSyncService statusSyncService;
    private final TripCompletionSnapshotFactory completionSnapshots;
    private final TripAnalysisExecutionRepository analyses;
    private final TripAnalysisExecutionUploadRepository includedUploads;
    private final TripEditAccessService access;
    private final TripMutationCoordinator mutations;
    private final AdditionalAttachmentUploadOutboxRepository outboxes;
    private final ObjectMapper json;

    @Transactional(readOnly = true)
    public TripProcessingStatusResponse findStatus(Long tripId, Long userId) {
        TripProcessingStatusResponse response = findOwnedStatus(tripId, userId);
        return analyses.findFirstByTripIdAndEndedAtIsNull(tripId).map(response::withAnalysis).orElse(response);
    }

    @Transactional(readOnly = true)
    public AdditionalAttachmentProcessingStatusResponse findAdditionalStatus(
            Long tripId,
            Long actorId,
            String additionId
    ) {
        access.findAccess(tripId, actorId);
        AdditionalAttachmentExecution addition = additionalExecutions.findById(additionId)
                .filter(found -> tripId.equals(found.getTripId())).orElseThrow(TripNotFoundException::new);
        TripAnalysisExecution execution = analyses.findFirstByTripIdAndEndedAtIsNull(tripId)
                .orElseGet(() -> analyses.findForAddition(additionId, PageRequest.of(0, 1)).stream()
                        .findFirst().orElse(null));
        boolean included = execution != null
                && includedUploads.existsByExecutionIdAndAdditionId(execution.getExecutionId(), additionId);
        boolean automaticRetryPending = !addition.isTerminal() && addition.getAnalysisFailureCount() > 0
                && (execution == null || execution.getEndedAt() != null);
        String blockedReason = execution != null
                && (execution.getStatus() == TripAnalysisExecution.Status.APPLY_BLOCKED
                || execution.isRequiresRemoteTerminationConfirmation() && execution.getRemoteTerminalConfirmedAt() == null)
                ? execution.getErrorCode() : null;
        return new AdditionalAttachmentProcessingStatusResponse(
                tripId,
                additionId,
                addition.getStatusVersion(),
                Status.valueOf(addition.getDisplayStatus().name()),
                addition.getCurrentStep(),
                execution == null ? null : execution.getExecutionId(),
                execution == null ? null : execution.getStatus(),
                included,
                addition.getAnalysisFailureCount(),
                automaticRetryPending,
                blockedReason
        );
    }

    private TripProcessingStatusResponse findOwnedStatus(Long tripId, Long userId) {
        Trip trip = findTrip(tripId, userId);
        Optional<AdditionalAttachmentExecution> addition =
                additionalExecutions.findFirstByTripIdAndUserIdAndEndedAtIsNullOrderByCreatedAtDescExecutionIdDesc(tripId, userId)
                        .or(() -> additionalExecutions.findFirstByTripIdAndUserIdOrderByCreatedAtDescExecutionIdDesc(tripId, userId));
        if (addition.isPresent()) {
            AdditionalAttachmentExecution execution = addition.get();
            return fromSnapshot(new TripProcessingStateSnapshot(
                    tripId,
                    execution.getExecutionId(),
                    execution.getStatusVersion(),
                    execution.getDisplayStatus(),
                    execution.getCurrentStep(),
                    execution.getErrorCode(),
                    execution.getDisplayStatus() == TripDisplayStatus.COMPLETED ? execution.getResultRawPayload() : null
            ));
        }
        Optional<TripCreationExecution> creation = creationExecutions.findByTripId(tripId);
        if (creation.isPresent()) {
            TripCreationExecution execution = creation.get();
            return fromSnapshot(new TripProcessingStateSnapshot(
                    tripId,
                    execution.getExecutionId(),
                    execution.getStatusVersion(),
                    execution.getDisplayStatus(),
                    execution.getCurrentStep(),
                    execution.getErrorCode(),
                    execution.getDisplayStatus() == TripDisplayStatus.COMPLETED ? execution.getResultRawPayload() : null
            ));
        }
        return findLegacyStatus(trip, userId);
    }

    public void synchronizeStatus(Long tripId, Long userId) {
        findTrip(tripId, userId);
        Optional<TripAnalysisExecution> active = analyses.findFirstByTripIdAndEndedAtIsNull(tripId);
        if (active.isPresent()) {
            synchronizeAnalysis(active.get().getExecutionId());
            return;
        }
        Optional<AdditionalAttachmentExecution> addition =
                additionalExecutions.findFirstByTripIdAndUserIdAndEndedAtIsNullOrderByCreatedAtDescExecutionIdDesc(tripId, userId)
                        .or(() -> additionalExecutions.findFirstByTripIdAndUserIdOrderByCreatedAtDescExecutionIdDesc(tripId, userId));
        if (addition.isPresent()) {
            synchronizeAddition(addition.get());
            return;
        }
        creationExecutions.findByTripId(tripId).ifPresent(this::synchronizeCreation);
    }

    public void synchronizeCreation(String executionId) {
        creationExecutions.findById(executionId).ifPresent(this::synchronizeCreation);
    }

    public void synchronizeAddition(String additionId) {
        additionalExecutions.findById(additionId).ifPresent(this::synchronizeAddition);
    }

    public void synchronizeAnalysis(String executionId) {
        TripAnalysisExecution execution = analyses.findById(executionId).orElseThrow();
        if (execution.getEndedAt() != null || execution.getResultRawPayload() != null
                || execution.getProcessPublishedAt() == null
                || execution.getStatus() != TripAnalysisExecution.Status.AI_REQUESTED) {
            return;
        }
        Map<String, Long> versions = includedUploads.findAllByExecutionIdOrderById(executionId).stream()
                .map(included -> additionalExecutions.findById(included.getAdditionId()).orElseThrow())
                .collect(Collectors.toMap(AdditionalAttachmentExecution::getExecutionId,
                        AdditionalAttachmentExecution::getStatusVersion));
        int expectedTotal = json.readTree(outboxes.findById(execution.getProcessOutboxId()).orElseThrow().getPayload())
                .path("attachments").size();
        TripPhotoAnalysisStatusResponse ai = analysis.findStatus(execution.getTripId(), executionId);
        if (ai.progress() != null && ai.progress().total() != expectedTotal) {
            throw new IllegalStateException(TripInternalErrorMessage.AI_PROGRESS_TOTAL_EXECUTION_MISMATCH.message());
        }
        TripDisplayStatus nextStatus = ai.status() == TripPhotoAnalysisStatusResponse.Status.COMPLETED
                ? TripDisplayStatus.FINALIZING : TripDisplayStatus.PROCESSING;
        String nextStep = ai.status() == TripPhotoAnalysisStatusResponse.Status.PROCESSING ? ai.currentStep() : null;
        mutations.run(execution.getTripId(), () ->
                statusSyncService.synchronizeAnalysis(executionId, versions, nextStatus, nextStep));
    }

    private void synchronizeAddition(AdditionalAttachmentExecution execution) {
        String executionId = execution.getExecutionId();
        long expectedVersion = execution.getStatusVersion();
        TripDisplayStatus nextStatus = execution.getDisplayStatus();
        String nextStep = execution.getCurrentStep();
        if (!execution.isTerminal()
                && execution.getStatus() != AdditionalAttachmentExecution.Status.RESULT_APPLYING
                && execution.getProcessPublishedAt() != null) {
            TripPhotoAnalysisStatusResponse ai = analysis.findStatus(execution.getTripId(), executionId);
            if (ai.progress() != null
                    && !Integer.valueOf(ai.progress().total()).equals(execution.getAnalysisAttachmentCount())) {
                throw new IllegalStateException(
                        TripInternalErrorMessage.AI_PROGRESS_TOTAL_EXECUTION_MISMATCH.message());
            }
            if (ai.status() == TripPhotoAnalysisStatusResponse.Status.COMPLETED) {
                nextStatus = TripDisplayStatus.FINALIZING;
                nextStep = null;
            } else {
                nextStatus = TripDisplayStatus.PROCESSING;
                nextStep = ai.currentStep();
            }
        }
        TripDisplayStatus status = nextStatus;
        String step = nextStep;
        mutations.run(execution.getTripId(), () ->
                statusSyncService.synchronizeAddition(executionId, expectedVersion, status, step));
    }

    private void synchronizeCreation(TripCreationExecution execution) {
        String executionId = execution.getExecutionId();
        long expectedVersion = execution.getStatusVersion();
        TripDisplayStatus nextStatus = execution.getDisplayStatus();
        String nextStep = execution.getCurrentStep();
        if (!execution.isTerminal()
                && execution.getStatus() != TripCreationExecution.Status.RESULT_APPLYING
                && execution.getProcessPublishedAt() != null) {
            TripPhotoAnalysisStatusResponse ai = analysis.findStatus(execution.getTripId(), executionId);
            if (ai.progress() != null && ai.progress().total() != execution.getTotalAttachmentCount()) {
                throw new IllegalStateException(
                        TripInternalErrorMessage.AI_PROGRESS_TOTAL_EXECUTION_MISMATCH.message());
            }
            if (ai.status() == TripPhotoAnalysisStatusResponse.Status.COMPLETED) {
                nextStatus = TripDisplayStatus.FINALIZING;
                nextStep = null;
            } else {
                nextStatus = TripDisplayStatus.PROCESSING;
                nextStep = ai.status() == TripPhotoAnalysisStatusResponse.Status.QUEUED ? null : ai.currentStep();
            }
        }
        TripDisplayStatus status = nextStatus;
        String step = nextStep;
        mutations.run(execution.getTripId(), () ->
                statusSyncService.synchronizeCreation(executionId, expectedVersion, status, step));
    }

    private TripProcessingStatusResponse findLegacyStatus(Trip trip, Long userId) {
        if (trip.getProcessingStatus() == ProcessingStatus.PROCESSING) {
            Optional<InitialAttachmentUploadBatch> latestBatch =
                    uploadBatches.findFirstByTripIdAndUserIdOrderByIdDesc(trip.getId(), userId);
            if (latestBatch.isPresent() && latestBatch.get().getLastBatch()
                    && latestBatch.get().getStatus() == InitialAttachmentUploadStatus.FAILED) {
                return new TripProcessingStatusResponse(
                        trip.getId(),
                        null,
                        0L,
                        Status.FAILED,
                        null,
                        null,
                        new TripProcessingStatusResponse.Error(
                                ErrorMessage.AI_PROCESSING_FAILED.name(),
                                TripInternalErrorMessage.ATTACHMENT_PROCESSING_FAILED.message()
                        )
                );
            }
        }
        return fromDatabase(trip, null, 0L);
    }

    private TripProcessingStatusResponse fromSnapshot(
            TripProcessingStateSnapshot snapshot
    ) {
        Status status = Status.valueOf(snapshot.displayStatus().name());

        if (status == Status.COMPLETED) {
            TripProcessingStatusResponse.Result savedResult = completionSnapshots.resultFromRaw(snapshot.tripId(), snapshot.completedRawPayload());
            if (savedResult != null) {
                return new TripProcessingStatusResponse(snapshot.tripId(), snapshot.executionId(), snapshot.statusVersion(),
                        Status.COMPLETED, null, savedResult, null);
            }
            return completed(
                    snapshot.tripId(),
                    snapshot.executionId(),
                    snapshot.statusVersion()
            );
        }

        TripProcessingStatusResponse.Error error = null;

        if (status == Status.FAILED) {
            error = new TripProcessingStatusResponse.Error(
                    snapshot.errorCode(),
                    TripInternalErrorMessage.ATTACHMENT_PROCESSING_FAILED.message()
            );
        }

        return new TripProcessingStatusResponse(
                snapshot.tripId(),
                snapshot.executionId(),
                snapshot.statusVersion(),
                status,
                snapshot.currentStep(),
                null,
                error
        );
    }

    private Trip findTrip(
            Long tripId,
            Long userId
    ) {
        return trips.findByIdAndUserIdAndDeletedAtIsNull(tripId, userId)
                .orElseThrow(TripNotFoundException::new);
    }

    private TripProcessingStatusResponse fromDatabase(
            Trip trip,
            String executionId,
            long statusVersion
    ) {
        Long tripId = trip.getId();
        return switch (trip.getProcessingStatus()) {
            case PROCESSING -> processing(tripId, executionId, statusVersion);
            case COMPLETED -> completed(tripId, executionId, statusVersion);
            case FAILED -> new TripProcessingStatusResponse(
                    tripId,
                    executionId,
                    statusVersion,
                    Status.FAILED,
                    null,
                    null,
                    new TripProcessingStatusResponse.Error(
                            ErrorMessage.AI_PROCESSING_FAILED.name(),
                            TripInternalErrorMessage.ATTACHMENT_PROCESSING_FAILED.message()
                    )
            );
            case CANCELED -> new TripProcessingStatusResponse(
                    tripId,
                    executionId,
                    statusVersion,
                    Status.CANCELED,
                    null,
                    null,
                    null
            );
        };
    }

    private TripProcessingStatusResponse processing(
            Long tripId,
            String executionId,
            long statusVersion
    ) {
        return new TripProcessingStatusResponse(
                tripId,
                executionId,
                statusVersion,
                Status.PROCESSING,
                null,
                null,
                null
        );
    }

    private TripProcessingStatusResponse completed(
            Long tripId,
            String executionId,
            long statusVersion
    ) {
        int placeCount = Math.toIntExact(places.countByTripIdAndDeletedAtIsNull(tripId));
        int classifiedCount = Math.toIntExact(
                attachments.countByTripIdAndDeletedAtIsNullAndClassificationStatus(
                        tripId, ClassificationStatus.ACTIVE)
        );
        int unclassifiedCount = Math.toIntExact(
                attachments.countByTripIdAndDeletedAtIsNullAndClassificationStatus(
                        tripId, ClassificationStatus.UNCLASSIFIED)
        );

        return new TripProcessingStatusResponse(
                tripId,
                executionId,
                statusVersion,
                Status.COMPLETED,
                null,
                new TripProcessingStatusResponse.Result(tripId, placeCount, classifiedCount, unclassifiedCount),
                null
        );
    }
}
