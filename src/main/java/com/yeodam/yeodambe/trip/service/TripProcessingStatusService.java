package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;


import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.integration.service.response.TripPhotoAnalysisStatusResponse;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
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

    public TripProcessingStatusResponse findStatus(
            Long tripId,
            Long userId
    ) {
        Trip trip = findTrip(tripId, userId);
        var addition = additionalExecutions.findFirstByTripIdAndUserIdOrderByCreatedAtDescExecutionIdDesc(tripId, userId);
        if (addition.isPresent()) {
            var execution = addition.get();
            switch (execution.getStatus()) {
                case FAILED -> { return new TripProcessingStatusResponse(tripId, Status.FAILED,  null, null,
                        new TripProcessingStatusResponse.Error(execution.getErrorCode(),
                                TripInternalErrorMessage.ATTACHMENT_PROCESSING_FAILED.message())); }
                case CANCELED -> { return new TripProcessingStatusResponse(tripId, Status.CANCELED, null, null, null); }
                case RESULT_APPLYING -> { return new TripProcessingStatusResponse(tripId, Status.FINALIZING, null, null, null); }
                case COMPLETED -> { return fromDatabase(trip); }
                default -> {
                    if (execution.getProcessPublishedAt() == null) return processing(tripId);
                    var ai = analysis.findStatus(tripId, execution.getExecutionId());
                    if (ai.progress() != null && !Integer.valueOf(ai.progress().total()).equals(execution.getAnalysisAttachmentCount()))
                        throw new IllegalStateException(TripInternalErrorMessage.AI_PROGRESS_TOTAL_EXECUTION_MISMATCH.message());
                    if (ai.status() == TripPhotoAnalysisStatusResponse.Status.COMPLETED)
                        return new TripProcessingStatusResponse(tripId, Status.FINALIZING, null, null, null);
                    return new TripProcessingStatusResponse(tripId, Status.PROCESSING,
                            ai.currentStep(), null, null);
                }
            }
        }

        if (trip.getProcessingStatus() != ProcessingStatus.PROCESSING) {
            return fromDatabase(trip);
        }
        var execution = creationExecutions.findByTripId(tripId);
        if (execution.isPresent()) {
            if (execution.get().getStatus() == TripCreationExecution.Status.RESULT_APPLYING) {
                return new TripProcessingStatusResponse(tripId, Status.FINALIZING,  null, null, null);
            }
            if (execution.get().getProcessPublishedAt() == null) {
                return processing(tripId);
            }
            TripPhotoAnalysisStatusResponse ai = analysis.findStatus(tripId, execution.get().getExecutionId());
            if (ai.progress() != null && ai.progress().total() != execution.get().getTotalAttachmentCount()) {
                throw new IllegalStateException(TripInternalErrorMessage.AI_PROGRESS_TOTAL_EXECUTION_MISMATCH.message());
            }
            if (ai.status() == TripPhotoAnalysisStatusResponse.Status.QUEUED) {
                return processing(tripId);
            }
            if (ai.status() == TripPhotoAnalysisStatusResponse.Status.COMPLETED) {
                return new TripProcessingStatusResponse(tripId, Status.FINALIZING,  null, null, null);
            }
            return new TripProcessingStatusResponse(tripId, Status.PROCESSING,
                    ai.currentStep(), null, null);
        }
        var latestBatch = uploadBatches.findFirstByTripIdAndUserIdOrderByIdDesc(tripId, userId);
        if (latestBatch.isPresent() && latestBatch.get().getLastBatch()
                && latestBatch.get().getStatus() == InitialAttachmentUploadStatus.FAILED) {
            return new TripProcessingStatusResponse(tripId, Status.FAILED, null, null,
                    new TripProcessingStatusResponse.Error(
                            ErrorMessage.AI_PROCESSING_FAILED.name(),
                            TripInternalErrorMessage.ATTACHMENT_PROCESSING_FAILED.message()
                    ));
        }
        if (!executions.isAnalysisStarted(tripId)
                && !uploadBatches.existsByTripIdAndStatus(tripId, InitialAttachmentUploadStatus.ANALYZING)) {
            return processing(tripId);
        }

        TripPhotoAnalysisStatusResponse aiStatus = analysis.findStatus(tripId);
        if (aiStatus.status() == TripPhotoAnalysisStatusResponse.Status.QUEUED) {
            return processing(tripId);
        }
        if (aiStatus.status() == TripPhotoAnalysisStatusResponse.Status.PROCESSING) {
            return new TripProcessingStatusResponse(
                    tripId,
                    Status.PROCESSING,
                    aiStatus.currentStep(),
                    null,
                    null
            );
        }

        Trip updated = findTrip(tripId, userId);
        if (aiStatus.status() == TripPhotoAnalysisStatusResponse.Status.COMPLETED
                && updated.getProcessingStatus() == ProcessingStatus.PROCESSING) {
            return new TripProcessingStatusResponse(tripId, Status.FINALIZING,  null, null, null);
        }
        return fromDatabase(updated);
    }

    private Trip findTrip(
            Long tripId,
            Long userId
    ) {
        return trips.findByIdAndUserIdAndDeletedAtIsNull(tripId, userId)
                .orElseThrow(TripNotFoundException::new);
    }

    private TripProcessingStatusResponse fromDatabase(Trip trip) {
        Long tripId = trip.getId();
        return switch (trip.getProcessingStatus()) {
            case PROCESSING -> processing(tripId);
            case COMPLETED -> completed(tripId);
            case FAILED -> new TripProcessingStatusResponse(
                    tripId,
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
                    Status.CANCELED,
                    null,
                    null,
                    null
            );
        };
    }

    private TripProcessingStatusResponse processing(Long tripId) {
        return new TripProcessingStatusResponse(
                tripId,
                Status.PROCESSING,
                null,
                null,
                null
        );
    }

    private TripProcessingStatusResponse completed(Long tripId) {
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
                Status.COMPLETED,
                null,
                new TripProcessingStatusResponse.Result(tripId, placeCount, classifiedCount, unclassifiedCount),
                null
        );
    }
}
