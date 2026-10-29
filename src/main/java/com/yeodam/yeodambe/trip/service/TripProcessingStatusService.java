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

@Service
@RequiredArgsConstructor
public class TripProcessingStatusService {
    private final TripRepository trips;
    private final TripDetailPlaceRepository places;
    private final TripAttachmentRepository attachments;
    private final TripPhotoAnalysisService analysis;
    private final InitialUploadExecutionRegistry executions;
    private final InitialAttachmentUploadBatchRepository uploadBatches;
    private final TripCreationExecutionRepository creationExecutions;
    private final AdditionalAttachmentExecutionRepository additionalExecutions;
    private final TripProcessingStatusSyncService statusSyncService;

    public TripProcessingStatusResponse findStatus(
            Long tripId,
            Long userId
    ) {
        Trip trip = findTrip(tripId, userId);
        var addition = additionalExecutions.findFirstByTripIdAndUserIdOrderByCreatedAtDescExecutionIdDesc(tripId, userId);
        if (addition.isPresent()) {
            var execution = addition.get();
            String executionId = execution.getExecutionId();
            long expectedVersion = execution.getStatusVersion();
            TripDisplayStatus nextStatus = execution.getDisplayStatus();
            String nextStep = execution.getCurrentStep();

            if (!execution.isTerminal()
                    && execution.getStatus() != AdditionalAttachmentExecution.Status.RESULT_APPLYING
                    && execution.getProcessPublishedAt() != null) {
                var ai = analysis.findStatus(tripId, executionId);
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

            var snapshot = statusSyncService.synchronizeAddition(
                    executionId, expectedVersion, nextStatus, nextStep);
            return fromSnapshot(snapshot);
        }

        var creation = creationExecutions.findByTripId(tripId);
        if (creation.isPresent()) {
            var execution = creation.get();
            String executionId = execution.getExecutionId();
            long expectedVersion = execution.getStatusVersion();
            TripDisplayStatus nextStatus = execution.getDisplayStatus();
            String nextStep = execution.getCurrentStep();

            if (!execution.isTerminal()
                    && execution.getStatus() != TripCreationExecution.Status.RESULT_APPLYING
                    && execution.getProcessPublishedAt() != null) {
                var ai = analysis.findStatus(tripId, executionId);
                if (ai.progress() != null && ai.progress().total() != execution.getTotalAttachmentCount()) {
                    throw new IllegalStateException(
                            TripInternalErrorMessage.AI_PROGRESS_TOTAL_EXECUTION_MISMATCH.message());
                }
                if (ai.status() == TripPhotoAnalysisStatusResponse.Status.COMPLETED) {
                    nextStatus = TripDisplayStatus.FINALIZING;
                    nextStep = null;
                } else {
                    nextStatus = TripDisplayStatus.PROCESSING;
                    nextStep = ai.status() == TripPhotoAnalysisStatusResponse.Status.QUEUED
                            ? null : ai.currentStep();
                }
            }

            var snapshot = statusSyncService.synchronizeCreation(
                    executionId, expectedVersion, nextStatus, nextStep);
            return fromSnapshot(snapshot);
        }

        String executionId = null;
        long statusVersion = 0L;
        if (trip.getProcessingStatus() != ProcessingStatus.PROCESSING) {
            return fromDatabase(trip, executionId, statusVersion);
        }
        var latestBatch = uploadBatches.findFirstByTripIdAndUserIdOrderByIdDesc(tripId, userId);
        if (latestBatch.isPresent() && latestBatch.get().getLastBatch()
                && latestBatch.get().getStatus() == InitialAttachmentUploadStatus.FAILED) {
            return new TripProcessingStatusResponse(tripId, executionId, statusVersion, Status.FAILED, null, null,
                    new TripProcessingStatusResponse.Error(
                            ErrorMessage.AI_PROCESSING_FAILED.name(),
                            TripInternalErrorMessage.ATTACHMENT_PROCESSING_FAILED.message()
                    ));
        }
        if (!executions.isAnalysisStarted(tripId)
                && !uploadBatches.existsByTripIdAndStatus(tripId, InitialAttachmentUploadStatus.ANALYZING)) {
            return processing(tripId, executionId, statusVersion);
        }

        TripPhotoAnalysisStatusResponse aiStatus = analysis.findStatus(tripId);
        if (aiStatus.status() == TripPhotoAnalysisStatusResponse.Status.QUEUED) {
            return processing(tripId, executionId, statusVersion);
        }
        if (aiStatus.status() == TripPhotoAnalysisStatusResponse.Status.PROCESSING) {
            return new TripProcessingStatusResponse(
                    tripId,
                    executionId,
                    statusVersion,
                    Status.PROCESSING,
                    aiStatus.currentStep(),
                    null,
                    null
            );
        }

        Trip updated = findTrip(tripId, userId);
        if (aiStatus.status() == TripPhotoAnalysisStatusResponse.Status.COMPLETED
                && updated.getProcessingStatus() == ProcessingStatus.PROCESSING) {
            return new TripProcessingStatusResponse(tripId, executionId, statusVersion, Status.FINALIZING,  null, null, null);
        }
        return fromDatabase(updated, executionId, statusVersion);
    }

    private TripProcessingStatusResponse fromSnapshot(
            TripProcessingStateSnapshot snapshot
    ) {
        Status status = Status.valueOf(snapshot.displayStatus().name());

        if (status == Status.COMPLETED) {
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
