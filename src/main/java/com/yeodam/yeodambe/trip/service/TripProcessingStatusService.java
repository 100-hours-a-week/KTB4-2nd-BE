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

import java.util.Optional;

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

    public TripProcessingStatusResponse findStatus(
            Long tripId,
            Long userId
    ) {
        Trip trip = findTrip(tripId, userId);
        Optional<AdditionalAttachmentExecution> addition =
                additionalExecutions.findFirstByTripIdAndUserIdOrderByCreatedAtDescExecutionIdDesc(tripId, userId);
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
        Optional<AdditionalAttachmentExecution> addition =
                additionalExecutions.findFirstByTripIdAndUserIdOrderByCreatedAtDescExecutionIdDesc(tripId, userId);
        if (addition.isPresent()) {
            synchronizeAddition(addition.get());
            return;
        }
        creationExecutions.findByTripId(tripId).ifPresent(this::synchronizeCreation);
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
        statusSyncService.synchronizeAddition(executionId, expectedVersion, nextStatus, nextStep);
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
        statusSyncService.synchronizeCreation(executionId, expectedVersion, nextStatus, nextStep);
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
