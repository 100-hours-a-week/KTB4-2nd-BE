package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionRoute;
import com.yeodam.yeodambe.common.logging.WorkflowMdcScope;
import com.yeodam.yeodambe.trip.service.response.AdditionalAttachmentUploadCompleteResponse;
import com.yeodam.yeodambe.common.exception.UnsupportedAttachmentFormatException;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadCompleteRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import lombok.extern.slf4j.Slf4j;
import com.yeodam.yeodambe.integration.service.request.PhotosReadyMessage;
import com.yeodam.yeodambe.integration.service.request.PhotoProcessMessage;

import java.util.List;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdditionalAttachmentUploadCompletionService {

    private final AdditionalAttachmentUploadTransactionService uploadTransactionService;
    private final AttachmentUploadedFileValidator uploadedFileValidator;
    private final AttachmentDerivativePreparationService attachmentDerivativePreparationService;
    private final TripAttachmentStorageClient tripAttachmentStorageClient;
    private final AdditionalAttachmentAnalysisPreparationService analysisPreparationService;
    private final TripMutationCoordinator mutations;

    public AdditionalAttachmentUploadCompleteResponse complete(
            Long tripId, Long userId, AdditionalAttachmentUploadCompleteRequest request) {
        verifyBatch(tripId, userId, request);
        AdditionalAttachmentUploadTransactionService.VerificationBatch batch = loadForVerification(tripId, userId, request.uploadId());
        return new AdditionalAttachmentUploadCompleteResponse(
                tripId, "QUEUED", batch.items().size());
    }

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
                loadForVerification(
                        tripId,
                        userId,
                        request.uploadId()
                );

        if (batch.status() != AdditionalAttachmentUploadStatus.PENDING) {
            return;
        }

        String jobId = batch.additionId();
        try (WorkflowMdcScope scope = WorkflowMdcScope.job("trip_reanalysis", tripId, jobId);
             WorkflowLog.Stage timing = startUploadAcceptanceLogging(batch.items().size())) {
            try {
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
            } catch (InvalidAttachmentUploadException
                     | UnsupportedAttachmentFormatException failure) {
                ErrorMessage error = failure instanceof UnsupportedAttachmentFormatException
                        ? ErrorMessage.UNSUPPORTED_ATTACHMENT_FORMAT
                        : ErrorMessage.INVALID_ATTACHMENT_UPLOAD;

                mutations.execute(tripId, () -> {
                    uploadTransactionService.failExecution(jobId, error.name());
                    return null;
                });

                throw failure;
            }

            mutations.execute(tripId, () -> {
                uploadTransactionService.markVerified(tripId, userId, request.uploadId());
                return null;
            });
            timing.success();
        }
    }
    public Optional<AdditionalAttachmentAnalysisPreparationService.PreparedAnalysis> prepareAddition(
            Long tripId, Long userId, AdditionalAttachmentUploadCompleteRequest request
    ) {
        if (request == null || request.uploadId() == null || request.uploadId().isBlank()) {
            throw new InvalidAttachmentUploadException();
        }
        AdditionalAttachmentUploadTransactionService.VerificationBatch batch = loadForVerification(tripId, userId, request.uploadId());
        requireLegacyRoute(batch);
        if (batch.status() == AdditionalAttachmentUploadStatus.COMPLETED) {
            return Optional.empty();
        }
        if (batch.status() != AdditionalAttachmentUploadStatus.PREPARED) {
            prepareBatchPhotos(tripId, userId, request);
        }
        return batch.lastBatch()
                ? Optional.of(mutations.execute(tripId, () -> analysisPreparationService.prepare(tripId, userId, request.uploadId())))
                : Optional.empty();
    }

    public List<DerivedPhotoKeys> prepareBatchPhotos(
            Long tripId, Long userId, AdditionalAttachmentUploadCompleteRequest request
    ) {
        verifyBatch(tripId, userId, request);
        requireLegacyRoute(loadForVerification(tripId, userId, request.uploadId()));
        AdditionalAttachmentUploadTransactionService.ConversionBatch claim = mutations.execute(tripId,
                () -> uploadTransactionService.claimConversion(tripId, userId, request.uploadId()));
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
            List<DerivedPhotoKeys> completed = derived;
            mutations.execute(tripId, () -> {
                uploadTransactionService.saveConverted(tripId, userId, request.uploadId(), claim.token(), completed);
                return null;
            });
            persisted = true;
            stage = "storage_retain";
            retainFiles(derived);
            return derived;
        } catch (RuntimeException failure) {
            logUploadPreparationFailure(tripId, request.uploadId(), stage, failure);
            if (!persisted) {
                boolean cleaned = !hasCleanupFailure(failure);
                for (DerivedPhotoKeys photo : derived) {
                    try {
                        uploadTransactionService.scheduleCleanupKeys(photo.originalKey(),
                                List.copyOf(keys(List.of(photo), false)), "CONVERSION_COMPENSATION");
                    } catch (RuntimeException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                        cleaned = false;
                    }
                }
                if (cleaned) {
                    try {
                        mutations.execute(tripId, () -> {
                            uploadTransactionService.releaseConversion(tripId, userId, request.uploadId(), claim.token());
                            return null;
                        });
                    } catch (RuntimeException releaseFailure) {
                        failure.addSuppressed(releaseFailure);
                    }
                }
            }
            throw failure;
        }
    }

    public PreparedQueueBatch prepareQueueBatch(
            Long tripId,
            Long userId,
            AdditionalAttachmentUploadCompleteRequest request
    ) {
        Optional<AdditionalAttachmentAnalysisPreparationService.PreparedAnalysis> analysis = prepareAddition(tripId, userId, request);

        AdditionalAttachmentUploadTransactionService.VerificationBatch batch = loadForVerification(
                tripId,
                userId,
                request.uploadId()
        );

        requireLegacyRoute(batch);
        String executionId = batch.additionId();

        PhotosReadyMessage ready = mutations.execute(tripId, () -> analysisPreparationService.photosReady(
                tripId, userId, request.uploadId(), executionId));

        return new PreparedQueueBatch(
                ready,
                analysis.map(value -> value.processMessage(executionId))
        );
    }

    public record PreparedQueueBatch(
            PhotosReadyMessage photosReady,
            Optional<PhotoProcessMessage> process
    ) {
    }

    private AdditionalAttachmentUploadTransactionService.VerificationBatch loadForVerification(
            Long tripId,
            Long actorId,
            String uploadId
    ) {
        uploadTransactionService.validateVerificationAccess(tripId, actorId, uploadId);
        return mutations.execute(tripId,
                () -> uploadTransactionService.loadForVerification(tripId, actorId, uploadId));
    }

    private void requireLegacyRoute(AdditionalAttachmentUploadTransactionService.VerificationBatch batch) {
        if (batch.conversionRoute() != AttachmentConversionRoute.DB) {
            throw new TripAttachmentAddNotAllowedException();
        }
    }

    private boolean hasCleanupFailure(Throwable failure) {
        return failure.getSuppressed().length > 0
                || (failure.getCause() != null
                && hasCleanupFailure(failure.getCause()));
    }

    private void retainFiles(List<DerivedPhotoKeys> derived) {
        tripAttachmentStorageClient.retain(List.copyOf(keys(derived, true)));
    }

    private Set<String> keys(List<DerivedPhotoKeys> derived, boolean includeOriginal) {
        Set<String> keys = new LinkedHashSet<>();
        for (DerivedPhotoKeys photo : derived) {
            if (includeOriginal) {
                keys.add(photo.originalKey());
            }
            keys.add(photo.analyzeKey());
            keys.add(photo.previewKey());
            if (photo.displayKey() != null && !photo.displayKey().isBlank()) {
                keys.add(photo.displayKey());
            }
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

    private WorkflowLog.Stage startUploadAcceptanceLogging(int photoCount) {
        return WorkflowLog.stage("original_upload_acceptance").field("photo_count", photoCount);
    }

    private void logUploadPreparationFailure(
            Long tripId, String uploadId, String stage, RuntimeException failure
    ) {
        log.atError().addKeyValue("event", "additional_upload_prepare")
                .addKeyValue("result", "failure").addKeyValue("trip_id", tripId)
                .addKeyValue("job_id", uploadId).addKeyValue("failure_stage", stage)
                .log("추가 사진 준비에 실패했습니다.", failure);
    }
}
