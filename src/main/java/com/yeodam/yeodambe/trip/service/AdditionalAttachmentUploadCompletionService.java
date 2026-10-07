package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadCompleteRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import lombok.extern.slf4j.Slf4j;
import com.yeodam.yeodambe.integration.service.request.PhotosReadyMessage;
import com.yeodam.yeodambe.integration.service.request.PhotoProcessMessage;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdditionalAttachmentUploadCompletionService {

    private final AdditionalAttachmentUploadTransactionService uploadTransactionService;
    private final AttachmentUploadedFileValidator uploadedFileValidator;
    private final AttachmentDerivativePreparationService attachmentDerivativePreparationService;
    private final TripAttachmentStorageClient tripAttachmentStorageClient;
    private final AdditionalAttachmentAnalysisPreparationService analysisPreparationService;

    public void verifyBatch(
            Long tripId,
            Long userId,
            AdditionalAttachmentUploadCompleteRequest request
    ) {
        if (request == null
                || request.uploadId() == null
                || request.uploadId().isBlank()) {
            throw new InvalidAttachmentUploadException();
        }

        AdditionalAttachmentUploadTransactionService.VerificationBatch batch =
                uploadTransactionService.loadForVerification(
                        tripId,
                        userId,
                        request.uploadId()
                );

        if (batch.status() != AdditionalAttachmentUploadStatus.PENDING) {
            return;
        }

        for (AdditionalAttachmentUploadItem item : batch.items()) {
            uploadedFileValidator.verifySize(
                    item.getObjectKey(),
                    item.getSizeBytes()
            );

            uploadedFileValidator.verifyType(
                    item.getObjectKey(),
                    item.getContentType()
            );
        }

        uploadTransactionService.markVerified(
                tripId,
                userId,
                request.uploadId()
        );
    }
    public Optional<AdditionalAttachmentAnalysisPreparationService.PreparedAnalysis> prepareAddition(
            Long tripId, Long userId, AdditionalAttachmentUploadCompleteRequest request
    ) {
        if (request == null || request.uploadId() == null || request.uploadId().isBlank()) {
            throw new InvalidAttachmentUploadException();
        }
        var batch = uploadTransactionService.loadForVerification(tripId, userId, request.uploadId());
        if (batch.status() == AdditionalAttachmentUploadStatus.COMPLETED) return Optional.empty();
        if (batch.status() != AdditionalAttachmentUploadStatus.PREPARED) {
            prepareBatchPhotos(tripId, userId, request);
        }
        return batch.lastBatch()
                ? Optional.of(analysisPreparationService.prepare(tripId, userId, request.uploadId()))
                : Optional.empty();
    }

    public List<DerivedPhotoKeys> prepareBatchPhotos(
            Long tripId, Long userId, AdditionalAttachmentUploadCompleteRequest request
    ) {
        verifyBatch(tripId, userId, request);
        var claim = uploadTransactionService.claimConversion(tripId, userId, request.uploadId());
        if (!claim.saved().isEmpty()) {
            retainFiles(claim.saved());
            return claim.saved();
        }
        List<DerivedPhotoKeys> derived = List.of();
        boolean persisted = false;
        String stage = "image_derivative";
        try {
            derived = createDerived(request.uploadId(), claim.items());
            stage = "attachment_persist";
            uploadTransactionService.saveConverted(tripId, userId, request.uploadId(), claim.token(), derived);
            persisted = true;
            stage = "storage_retain";
            retainFiles(derived);
            return derived;
        } catch (RuntimeException failure) {
            log.atError().addKeyValue("event", "additional_upload_prepare")
                    .addKeyValue("result", "failure").addKeyValue("trip_id", tripId)
                    .addKeyValue("job_id", request.uploadId()).addKeyValue("failure_stage", stage)
                    .log("추가 사진 준비에 실패했습니다.", failure);
            if (!persisted) {
                boolean cleaned = !hasCleanupFailure(failure);
                for (String key : keys(derived, false)) {
                    try { tripAttachmentStorageClient.delete(key); }
                    catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); cleaned = false; }
                }
                if (cleaned) {
                    try { uploadTransactionService.releaseConversion(tripId, userId, request.uploadId(), claim.token()); }
                    catch (RuntimeException releaseFailure) { failure.addSuppressed(releaseFailure); }
                }
            }
            throw failure;
        }
    }

    public PreparedQueueBatch prepareQueueBatch(
            Long tripId, Long userId, AdditionalAttachmentUploadCompleteRequest request, String executionId
    ) {
        if (executionId == null || executionId.isBlank()) throw new InvalidAttachmentUploadException();
        var analysis = prepareAddition(tripId, userId, request);
        PhotosReadyMessage ready = analysisPreparationService.photosReady(tripId, userId, request.uploadId(), executionId);
        return new PreparedQueueBatch(ready, analysis.map(value -> value.processMessage(executionId)));
    }

    public record PreparedQueueBatch(PhotosReadyMessage photosReady, Optional<PhotoProcessMessage> process) {}

    private boolean hasCleanupFailure(Throwable failure) {
        return failure.getSuppressed().length > 0
                || (failure.getCause() != null && hasCleanupFailure(failure.getCause()));
    }

    private void retainFiles(List<DerivedPhotoKeys> derived) {
        tripAttachmentStorageClient.retain(List.copyOf(keys(derived, true)));
    }

    private Set<String> keys(List<DerivedPhotoKeys> derived, boolean includeOriginal) {
        Set<String> keys = new LinkedHashSet<>();
        for (DerivedPhotoKeys photo : derived) {
            if (includeOriginal) keys.add(photo.originalKey());
            keys.add(photo.analyzeKey());
            keys.add(photo.previewKey());
            if (photo.displayKey() != null && !photo.displayKey().isBlank()) keys.add(photo.displayKey());
        }
        return keys;
    }

    List<DerivedPhotoKeys> createDerived(
            String uploadId,
            List<AdditionalAttachmentUploadItem> uploadItems
    ) {
        List<String> originalKeys = uploadItems.stream()
                .map(AdditionalAttachmentUploadItem::getObjectKey)
                .toList();

        List<String> contentTypes = uploadItems.stream()
                .map(AdditionalAttachmentUploadItem::getContentType)
                .toList();

        return attachmentDerivativePreparationService.create(
                uploadId,
                originalKeys,
                contentTypes
        );
    }
}
