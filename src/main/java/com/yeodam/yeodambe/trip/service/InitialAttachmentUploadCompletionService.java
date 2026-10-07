package com.yeodam.yeodambe.trip.service;


import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.integration.service.request.TripPhotoAnalysisRequest;
import com.yeodam.yeodambe.common.exception.AiProcessingFailedException;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadCompleteRequest;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import lombok.extern.slf4j.Slf4j;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class InitialAttachmentUploadCompletionService {

    private final TripAttachmentStorageClient storage;
    private final AttachmentDerivativePreparationService attachmentDerivativePreparationService;
    private final InitialAttachmentUploadTransactionService transactions;
    private final InitialAttachmentUploadItemRepository items;
    private final TripRegionRepository regions;
    private final TripPhotoAnalysisService analysis;
    private final TripPlaceNameService placeNames;
    private final TripAnalysisResultService results;
    private final TripProcessingStatusService statuses;
    private final AttachmentUploadedFileValidator attachmentUploadedFileValidator;

    public Optional<TripProcessingStatusResponse> complete(
            Long tripId, Long userId, InitialAttachmentUploadCompleteRequest request
    ) {
        if (request == null) throw new InvalidAttachmentUploadException();
        InitialAttachmentUploadBatch batch = transactions.startProcessing(tripId, userId, request.uploadId());
        if (batch.getStatus() == InitialAttachmentUploadStatus.COMPLETED) {
            return batch.getLastBatch() ? Optional.of(statuses.findStatus(tripId, userId)) : Optional.empty();
        }

        List<DerivedPhotoKeys> derived = List.of();
        boolean analysisPhase = false;
        String stage = "original_verify";
        try {
            List<InitialAttachmentUploadItem> uploadItems = items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId());
            if (uploadItems.stream().anyMatch(item -> item.getTripAttachmentId() != null)) {
                derived = transactions.loadBatchPhotos(batch.getId()).metadata();
            }
            verifyUploadedFiles(uploadItems);
            if (derived.isEmpty()) {
                stage = "image_derivative";
                derived = createDerived(batch.getExecutionId(), uploadItems);
                stage = "attachment_persist";
                transactions.saveAttachments(tripId, userId, batch.getUploadId(), derived);
            }
            stage = "storage_retain";
            retainFiles(derived);
            if (!batch.getLastBatch()) {
                stage = "batch_complete";
                transactions.completeBatch(tripId, userId, batch.getUploadId());
                return Optional.empty();
            }

            analysisPhase = true;
            stage = "analysis_prepare";
            var execution = transactions.loadExecution(tripId, userId, batch.getUploadId());
            stage = "ai_request";
            var result = analysis.analyze(tripId, batch.getExecutionId(),
                    analysisRequest(batch.getExecutionId(), execution.trip(), execution.photos()),
                    () -> transactions.startAnalysis(tripId, userId, batch.getUploadId()));
            stage = "place_names";
            var names = placeNames.resolve(tripId, batch.getExecutionId(), result,
                    () -> transactions.isAnalyzing(tripId, userId, batch.getUploadId()));
            stage = "result_persist";
            results.saveDirectUploadCompleted(tripId, userId, batch.getUploadId(),
                    execution.photos().attachments(), result, names);
        } catch (RuntimeException failure) {
            log.atError().addKeyValue("event", "initial_upload_completion")
                    .addKeyValue("result", "failure").addKeyValue("trip_id", tripId)
                    .addKeyValue("job_id", batch.getExecutionId()).addKeyValue("failure_stage", stage)
                    .log("직접 업로드 완료 처리에 실패했습니다.", failure);
            try {
                if (analysisPhase) {
                    transactions.failAnalysis(tripId, userId, batch.getUploadId());
                } else if (!hasCleanupFailure(failure)) {
                    List<DerivedPhotoKeys> cleanupTargets = derived;
                    transactions.failBatchAfterCleanup(tripId, userId, batch.getUploadId(),
                            () -> cleanupDerived(cleanupTargets, failure));
                }
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof AiProcessingFailedException aiFailure && failure.getSuppressed().length == 0) {
                return Optional.of(new TripProcessingStatusResponse(tripId,
                        TripProcessingStatusResponse.Status.FAILED,
                        new TripProcessingStatusResponse.Progress(aiFailure.getDone(), aiFailure.getTotal()),
                        aiFailure.getCurrentStep(), null,
                        new TripProcessingStatusResponse.Error(aiFailure.getCode(), aiFailure.getPublicMessage())));
            }
            throw failure;
        }
        return Optional.of(statuses.findStatus(tripId, userId));
    }

    private boolean hasCleanupFailure(Throwable failure) {
        return failure.getSuppressed().length > 0
                || (failure.getCause() != null && hasCleanupFailure(failure.getCause()));
    }

    private TripPhotoAnalysisRequest analysisRequest(
            String executionId, Trip trip, InitialAttachmentUploadTransactionService.Photos photos
    ) {
        var coordinates = regions.findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(trip.getId()).stream()
                .map(region -> new TripPhotoAnalysisRequest.Region(region.getLatitude(), region.getLongitude()))
                .toList();
        if (coordinates.isEmpty()) {
            throw new IllegalStateException(TripInternalErrorMessage.TRIP_REGION_MISSING.message());
        }
        List<TripPhotoAnalysisRequest.Photo> requestPhotos = new ArrayList<>();
        for (int i = 0; i < photos.attachments().size(); i++) {
            TripAttachment attachment = photos.attachments().get(i);
            DerivedPhotoKeys metadata = photos.metadata().get(i);
            requestPhotos.add(new TripPhotoAnalysisRequest.Photo(attachment.getId(), attachment.getAnalyzeStorageKey(),
                    metadata.takenAt(), metadata.latitude(), metadata.longitude(), metadata.deviceModel()));
        }
        return new TripPhotoAnalysisRequest(executionId, trip.getTripName(),
                new TripPhotoAnalysisRequest.Period(trip.getStartDate(), trip.getEndDate()), coordinates, requestPhotos);
    }

    void verifyUploadedFiles(List<InitialAttachmentUploadItem> uploadItems) {
        for (InitialAttachmentUploadItem item : uploadItems) {
            attachmentUploadedFileValidator.verifySize(
                    item.getObjectKey(),
                    item.getSizeBytes()
            );

            attachmentUploadedFileValidator.verifyType(
                    item.getObjectKey(),
                    item.getContentType()
            );
        }
    }

    List<DerivedPhotoKeys> createDerived(
            String executionId,
            List<InitialAttachmentUploadItem> uploadItems
    ) {
        List<String> originalKeys = uploadItems.stream()
                .map(InitialAttachmentUploadItem::getObjectKey)
                .toList();

        List<String> contentTypes = uploadItems.stream()
                .map(InitialAttachmentUploadItem::getContentType)
                .toList();

        return attachmentDerivativePreparationService.create(
                executionId,
                originalKeys,
                contentTypes
        );
    }

    void retainFiles(List<DerivedPhotoKeys> derived) {
        Set<String> objectKeys = new LinkedHashSet<>();

        for (DerivedPhotoKeys photo : derived) {
            objectKeys.add(photo.originalKey());
            objectKeys.add(photo.analyzeKey());
            objectKeys.add(photo.previewKey());

            if (photo.displayKey() != null && !photo.displayKey().isBlank()) {
                objectKeys.add(photo.displayKey());
            }
        }

        storage.retain(List.copyOf(objectKeys));
    }

    boolean cleanupDerived(
            List<DerivedPhotoKeys> derived,
            RuntimeException failure
    ) {
        Set<String> originalKeys = new LinkedHashSet<>();
        Set<String> derivedKeys = new LinkedHashSet<>();

        for (DerivedPhotoKeys photo : derived) {
            originalKeys.add(photo.originalKey());
            derivedKeys.add(photo.analyzeKey());
            derivedKeys.add(photo.previewKey());

            if (photo.displayKey() != null && !photo.displayKey().isBlank()) {
                derivedKeys.add(photo.displayKey());
            }
        }

        derivedKeys.removeAll(originalKeys);

        boolean cleaned = true;

        for (String key : derivedKeys) {
            try {
                storage.delete(key);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
                cleaned = false;
            }
        }

        return cleaned;
    }

}
