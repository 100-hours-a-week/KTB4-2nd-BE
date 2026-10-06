package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;

import com.yeodam.yeodambe.common.exception.*;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.integration.service.request.TripPhotoAnalysisRequest;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse.Status;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import org.slf4j.spi.LoggingEventBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripAttachmentService {
    private final TripRepository trips;
    private final TripRegionRepository regions;
    private final TripAttachmentStorageClient storage;
    private final TripAttachmentTransactionService transactions;
    private final TripAttachmentDerivativeService derivatives;
    private final TripPhotoAnalysisService analysis;
    private final TripPlaceNameService placeNames;
    private final TripAnalysisResultService results;
    private final InitialUploadExecutionRegistry executions;
    private final TripProcessingStatusService statuses;

    public TripProcessingStatusResponse uploadInitialAttachments(
            Long tripId, Long userId, List<MultipartFile> files) {
        Trip trip = trips.findById(tripId)
                .orElseThrow(TripNotFoundException::new);

        if (trip.getDeletedAt() != null || !trip.getUserId().equals(userId)) throw new TripNotFoundException();
        if (trip.getProcessingStatus() != ProcessingStatus.PROCESSING
                && trip.getProcessingStatus() != ProcessingStatus.FAILED) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        List<String> types = validate(files);
        TripAttachmentTransactionService.Reservation reservation = transactions.reserve(tripId, userId);
        String executionId = reservation.executionId();
        log.atInfo()
                .addKeyValue("event", "trip_creation")
                .addKeyValue("result", "started")
                .addKeyValue("trip_id", tripId)
                .addKeyValue("job_id", executionId)
                .addKeyValue("expected_count", files.size())
                .log("여행 생성 사진 처리를 시작했습니다.");
        deleteStaleObjects(reservation.staleObjectKeys());

        List<String> originalsKeys = new ArrayList<>();
        List<StoredFile> originalFiles = List.of();
        List<DerivedPhotoKeys> derivedKeys = List.of();
        List<TripAttachment> savedAttachments = List.of();
        boolean originalsSaved = false;
        String failureStage = "original_store";

        try {
            log.atInfo()
                    .addKeyValue("event", "photo_originals_saved")
                    .addKeyValue("result", "started")
                    .addKeyValue("trip_id", tripId)
                    .addKeyValue("job_id", executionId)
                    .addKeyValue("expected_count", files.size())
                    .addKeyValue("saved_count", 0)
                    .log("사진 원본 저장을 시작했습니다.");

            storeOriginals(executionId, files, originalsKeys);
            originalsSaved = true;

            log.atInfo()
                    .addKeyValue("event", "photo_originals_saved")
                    .addKeyValue("result", "success")
                    .addKeyValue("trip_id", tripId)
                    .addKeyValue("job_id", executionId)
                    .addKeyValue("expected_count", files.size())
                    .addKeyValue("saved_count", originalsKeys.size())
                    .log("사진 원본 저장을 완료했습니다.");

            failureStage = "derivative_create";
            derivedKeys = createDerived(tripId, executionId, originalsKeys, types);

            failureStage = "attachment_persist";
            TripAttachmentTransactionService.SavedAttachments persisted = transactions
                    .saveFilesAndAttachments(
                            tripId,
                            userId,
                            executionId,
                            files,
                            originalsKeys,
                            types,
                            derivedKeys
                    );
            originalFiles = persisted.originals();
            savedAttachments = persisted.attachments();

            failureStage = "processing_check";
            requireProcessing(tripId, userId);

            failureStage = "ai_request";
            JsonNode result = analysis.analyze(
                    tripId,
                    executionId,
                    analysisRequest(executionId, trip, savedAttachments, derivedKeys),
                    () -> executions.markAnalysisStarted(tripId, executionId)
            );
            Map<String, String> resolvedNames = placeNames.resolve(tripId, executionId, result);

            failureStage = "storage_retain";
            storage.retain(List.copyOf(objectKeys(originalsKeys, derivedKeys)));

            failureStage = "result_persist";
            results.saveCompleted(tripId, userId, executionId, savedAttachments, result, resolvedNames);
            log.atInfo()
                    .addKeyValue("event", "trip_creation")
                    .addKeyValue("result", "success")
                    .addKeyValue("trip_id", tripId)
                    .addKeyValue("job_id", executionId)
                    .addKeyValue("expected_count", files.size())
                    .addKeyValue("saved_count", originalsKeys.size())
                    .log("여행 생성 사진 처리를 완료했습니다.");
        } catch (AiProcessingFailedException failure) {
            log.atWarn()
                    .addKeyValue("event", "trip_creation")
                    .addKeyValue("result", "failure")
                    .addKeyValue("trip_id", tripId)
                    .addKeyValue("job_id", executionId)
                    .addKeyValue("expected_count", files.size())
                    .addKeyValue("saved_count", originalsKeys.size())
                    .addKeyValue("failure_stage", "ai_request")
                    .addKeyValue("error_code", failure.getCode())
                    .log("AI 사진 분석이 실패했습니다.", failure);
            cleanupFailure(tripId, userId, executionId, originalsKeys, derivedKeys,
                    originalFiles, savedAttachments, failure);
            if (failure.getSuppressed().length > 0) throw failure;
            return new TripProcessingStatusResponse(
                    failure.getTripId(),
                    Status.FAILED,
                    new TripProcessingStatusResponse.Progress(failure.getDone(), failure.getTotal()),
                    failure.getCurrentStep(),
                    null,
                    new TripProcessingStatusResponse.Error(
                            failure.getCode(), failure.getPublicMessage())
            );

        } catch (RuntimeException failure) {
            logTripCreationRuntimeFailure(
                    tripId,
                    executionId,
                    files.size(),
                    originalsKeys.size(),
                    failureStage,
                    failure
            );
            if (!originalsSaved) {
                log.atWarn()
                        .addKeyValue("event", "photo_originals_saved")
                        .addKeyValue("result", "failure")
                        .addKeyValue("trip_id", tripId)
                        .addKeyValue("job_id", executionId)
                        .addKeyValue("expected_count", files.size())
                        .addKeyValue("saved_count", originalsKeys.size())
                        .addKeyValue("failure_stage", "original_store")
                        .addKeyValue("error_code", "INTERNAL_SERVER_ERROR")
                        .log("사진 원본 저장에 실패했습니다.", failure);
            }

            cleanupFailure(tripId, userId, executionId, originalsKeys, derivedKeys,
                    originalFiles, savedAttachments, failure);
            throw failure;

        } finally {
            executions.release(tripId, executionId);
        }

        return statuses.findStatus(tripId, userId);
    }

    public Optional<TripProcessingStatusResponse> uploadInitialAttachments(
            Long tripId,
            Long userId,
            List<MultipartFile> files,
            int batchNo,
            int totalAttachmentCount,
            boolean complete
    ) {
        Trip trip = trips.findById(tripId)
                .orElseThrow(TripNotFoundException::new);

        if (trip.getDeletedAt() != null || !trip.getUserId().equals(userId)) throw new TripNotFoundException();
        if (trip.getProcessingStatus() != ProcessingStatus.PROCESSING
                && trip.getProcessingStatus() != ProcessingStatus.FAILED) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        List<String> types = validate(files);
        long batchBytes = files.stream().mapToLong(MultipartFile::getSize).sum();
        TripAttachmentTransactionService.Reservation reservation = transactions.reserveBatch(
                tripId, userId, batchNo, totalAttachmentCount);
        String executionId = reservation.executionId();
        if (batchNo == 1) {
            log.atInfo()
                    .addKeyValue("event", "trip_creation")
                    .addKeyValue("result", "started")
                    .addKeyValue("trip_id", tripId)
                    .addKeyValue("job_id", executionId)
                    .addKeyValue("expected_count", totalAttachmentCount)
                    .log("여행 생성 사진 처리를 시작했습니다.");
        }
        deleteStaleObjects(reservation.staleObjectKeys());

        List<String> originalKeys = new ArrayList<>();
        List<DerivedPhotoKeys> derivedKeys = List.of();
        TripAttachmentTransactionService.SavedAttachments persisted = null;
        InitialUploadExecutionRegistry.Snapshot snapshot = null;
        boolean finalBatchReady = false;
        boolean resultSaved = false;
        boolean originalsSaved = false;
        String failureStage = "original_store";

        try {
            log.atInfo()
                    .addKeyValue("event", "photo_originals_saved")
                    .addKeyValue("result", "started")
                    .addKeyValue("trip_id", tripId)
                    .addKeyValue("job_id", executionId)
                    .addKeyValue("expected_count", files.size())
                    .addKeyValue("saved_count", 0)
                    .log("사진 원본 저장을 시작했습니다.");

            storeOriginals(executionId, files, originalKeys);
            originalsSaved = true;

            log.atInfo()
                    .addKeyValue("event", "photo_originals_saved")
                    .addKeyValue("result", "success")
                    .addKeyValue("trip_id", tripId)
                    .addKeyValue("job_id", executionId)
                    .addKeyValue("expected_count", files.size())
                    .addKeyValue("saved_count", originalKeys.size())
                    .log("사진 원본 저장을 완료했습니다.");

            failureStage = "derivative_create";
            derivedKeys = createDerived(tripId, executionId, originalKeys, types);

            failureStage = "attachment_persist";
            persisted = transactions.saveFilesAndAttachments(
                    tripId, userId, executionId, files, originalKeys, types, derivedKeys);

            failureStage = "storage_retain";
            storage.retain(List.copyOf(objectKeys(originalKeys, derivedKeys)));

            failureStage = "execution_checkpoint";
            snapshot = executions.completeBatch(
                    tripId,
                    executionId,
                    batchNo,
                    batchBytes,
                    storedPhotos(persisted, derivedKeys),
                    complete
            );
            if (!complete) return Optional.empty();
            finalBatchReady = true;

            failureStage = "processing_check";
            requireProcessing(tripId, userId);
            List<TripAttachment> allAttachments = snapshot.photos().stream()
                    .map(InitialUploadExecutionRegistry.StoredPhoto::attachment)
                    .toList();
            List<DerivedPhotoKeys> allMetadata = snapshot.photos().stream()
                    .map(InitialUploadExecutionRegistry.StoredPhoto::metadata)
                    .toList();

            failureStage = "ai_request";
            JsonNode result = analysis.analyze(
                    tripId,
                    executionId,
                    analysisRequest(executionId, trip, allAttachments, allMetadata),
                    () -> executions.markAnalysisStarted(tripId, executionId)
            );
            failureStage = "result_persist";
            Map<String, String> resolvedNames = placeNames.resolve(tripId, executionId, result);
            results.saveCompleted(tripId, userId, executionId, allAttachments, result, resolvedNames);
            resultSaved = true;

            log.atInfo()
                    .addKeyValue("event", "trip_creation")
                    .addKeyValue("result", "success")
                    .addKeyValue("trip_id", tripId)
                    .addKeyValue("job_id", executionId)
                    .addKeyValue("expected_count", totalAttachmentCount)
                    .addKeyValue("saved_count", snapshot.photos().size())
                    .log("여행 생성 사진 처리를 완료했습니다.");

            return Optional.of(statuses.findStatus(tripId, userId));

        } catch (AiProcessingFailedException failure) {
            log.atWarn()
                    .addKeyValue("event", "trip_creation")
                    .addKeyValue("result", "failure")
                    .addKeyValue("trip_id", tripId)
                    .addKeyValue("job_id", executionId)
                    .addKeyValue("expected_count", totalAttachmentCount)
                    .addKeyValue("saved_count", snapshot.photos().size())
                    .addKeyValue("failure_stage", "ai_request")
                    .addKeyValue("error_code", failure.getCode())
                    .log("AI 사진 분석이 실패했습니다.", failure);

            InitialUploadExecutionRegistry.Snapshot failed = snapshot;
            if (failed == null) {
                cleanupBatch(tripId, executionId, batchNo, originalKeys, derivedKeys, persisted, failure);
                throw failure;
            }
            cleanupExecution(tripId, userId, executionId, failed, failure);
            if (failure.getSuppressed().length > 0) throw failure;
            return Optional.of(new TripProcessingStatusResponse(
                    failure.getTripId(),
                    Status.FAILED,
                    new TripProcessingStatusResponse.Progress(failure.getDone(), failure.getTotal()),
                    failure.getCurrentStep(),
                    null,
                    new TripProcessingStatusResponse.Error(failure.getCode(), failure.getPublicMessage())
            ));

        } catch (RuntimeException failure) {
            int savedCount = snapshot == null
                    ? originalKeys.size()
                    : snapshot.photos().size();

            logTripCreationRuntimeFailure(
                    tripId,
                    executionId,
                    totalAttachmentCount,
                    savedCount,
                    failureStage,
                    failure
            );

            if (!originalsSaved) {
                log.atWarn()
                        .addKeyValue("event", "photo_originals_saved")
                        .addKeyValue("result", "failure")
                        .addKeyValue("trip_id", tripId)
                        .addKeyValue("job_id", executionId)
                        .addKeyValue("expected_count", files.size())
                        .addKeyValue("saved_count", originalKeys.size())
                        .addKeyValue("failure_stage", "original_store")
                        .addKeyValue("error_code", "INTERNAL_SERVER_ERROR")
                        .log("사진 원본 저장에 실패했습니다.", failure);
            }

            if (!resultSaved) {
                if (finalBatchReady) {
                    cleanupExecution(tripId, userId, executionId, snapshot, failure);
                } else {
                    cleanupBatch(tripId, executionId, batchNo, originalKeys, derivedKeys, persisted, failure);
                }
            }
            throw failure;

        } finally {
            if (finalBatchReady) {
                executions.release(tripId, executionId);
            }
        }
    }

    private List<InitialUploadExecutionRegistry.StoredPhoto> storedPhotos(
            TripAttachmentTransactionService.SavedAttachments persisted,
            List<DerivedPhotoKeys> metadata
    ) {
        List<InitialUploadExecutionRegistry.StoredPhoto> photos = new ArrayList<>(metadata.size());
        for (int i = 0; i < metadata.size(); i++) {
            photos.add(new InitialUploadExecutionRegistry.StoredPhoto(
                    persisted.originals().get(i), persisted.attachments().get(i), metadata.get(i)));
        }
        return List.copyOf(photos);
    }

    private void cleanupBatch(
            Long tripId,
            String executionId,
            int batchNo,
            List<String> originalKeys,
            List<DerivedPhotoKeys> derivedKeys,
            TripAttachmentTransactionService.SavedAttachments persisted,
            RuntimeException failure
    ) {
        try {
            if (persisted != null) {
                transactions.deleteBatchReferences(
                        persisted.originals().stream().map(StoredFile::getId).toList(),
                        persisted.attachments().stream().map(TripAttachment::getId).toList());
            }
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        for (String key : objectKeys(originalKeys, derivedKeys)) delete(key, failure);
        executions.failBatch(tripId, executionId, batchNo);
    }

    private void cleanupExecution(
            Long tripId,
            Long userId,
            String executionId,
            InitialUploadExecutionRegistry.Snapshot snapshot,
            RuntimeException failure
    ) {
        List<StoredFile> originals = snapshot.photos().stream()
                .map(InitialUploadExecutionRegistry.StoredPhoto::original)
                .toList();
        List<TripAttachment> attachments = snapshot.photos().stream()
                .map(InitialUploadExecutionRegistry.StoredPhoto::attachment)
                .toList();
        try {
            transactions.failAndDeleteReference(
                    tripId,
                    userId,
                    executionId,
                    originals.stream().map(StoredFile::getId).toList(),
                    attachments.stream().map(TripAttachment::getId).toList());
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        for (InitialUploadExecutionRegistry.StoredPhoto photo : snapshot.photos()) {
            delete(photo.original().getObjectKey(), failure);
            delete(photo.metadata().analyzeKey(), failure);
            delete(photo.metadata().previewKey(), failure);
            if (photo.metadata().displayKey() != null
                    && !photo.metadata().displayKey().isBlank()) {
                delete(photo.metadata().displayKey(), failure);
            }
        }
    }

    private void logTripCreationRuntimeFailure(
            Long tripId,
            String executionId,
            int expectedCount,
            int savedCount,
            String failureStage,
            RuntimeException failure
    ) {
        ErrorMessage errorMessage =
                failure instanceof TripInitialAttachmentUploadNotAllowedException
                        ? ErrorMessage.TRIP_INITIAL_ATTACHMENT_UPLOAD_NOT_ALLOWED
                        : ErrorMessage.INTERNAL_SERVER_ERROR;

        LoggingEventBuilder logEvent =
                errorMessage == ErrorMessage.INTERNAL_SERVER_ERROR
                        ? log.atError()
                        : log.atWarn();

        logEvent.addKeyValue("event", "trip_creation")
                .addKeyValue("result", "failure")
                .addKeyValue("trip_id", tripId)
                .addKeyValue("job_id", executionId)
                .addKeyValue("expected_count", expectedCount)
                .addKeyValue("saved_count", savedCount)
                .addKeyValue("failure_stage", failureStage)
                .addKeyValue("error_code", errorMessage.name())
                .log("여행 생성 사진 처리에 실패했습니다.", failure);
    }

    private void requireProcessing(Long tripId, Long userId) {
        if (!trips.existsByIdAndUserIdAndDeletedAtIsNullAndProcessingStatus(
                tripId, userId, ProcessingStatus.PROCESSING)) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }
    }

    private void deleteStaleObjects(List<String> staleKeys) {
        for (String key : staleKeys) {
            try {
                storage.delete(key);
            } catch (RuntimeException ignored) {
                // 임시 태그가 남은 객체는 S3 Lifecycle이 정리한다.
            }
        }
    }

    private void storeOriginals(String executionId, List<MultipartFile> files, List<String> originalsKeys) {
        for (MultipartFile file : files) {
            String key;
            try {
                key = storage.store(executionId, file);
            } catch (AttachmentStorageException failure) {
                originalsKeys.add(failure.getObjectKey());
                throw failure;
            }
            if (key == null || key.isBlank()) throw new IllegalStateException(TripInternalErrorMessage.S3_OBJECT_KEY_MISSING.message());
            originalsKeys.add(key);
        }
    }

    private List<DerivedPhotoKeys> createDerived(
            Long tripId, String executionId, List<String> originalsKeys, List<String> mimeTypes
    ) {
        List<DerivedPhotoKeys> derived = derivatives.createAll(
                executionId, List.copyOf(originalsKeys), mimeTypes,
                () -> executions.isCurrent(tripId, executionId)).join();
        if (derived == null || derived.size() != mimeTypes.size()) {
            throw new IllegalStateException(TripInternalErrorMessage.DERIVED_ATTACHMENT_COUNT_MISMATCH.message());
        }

        for (int i = 0; i < derived.size(); i++) {
            DerivedPhotoKeys keys = derived.get(i);
            if (keys == null || !originalsKeys.get(i).equals(keys.originalKey())
                    || keys.analyzeKey() == null || keys.analyzeKey().isBlank()
                    || keys.previewKey() == null || keys.previewKey().isBlank()
                    || ("image/heic".equals(mimeTypes.get(i))
                    && (keys.displayKey() == null || keys.displayKey().isBlank()))) {
                throw new IllegalStateException(TripInternalErrorMessage.DERIVED_ATTACHMENT_RESULT_INVALID.message());
            }
        }
        return derived;
    }

    private List<String> objectKeys(List<String> originalsKeys, List<DerivedPhotoKeys> derivedKeys) {
        List<String> keys = new ArrayList<>(originalsKeys);
        for (DerivedPhotoKeys photo : derivedKeys) {
            keys.add(photo.analyzeKey());
            keys.add(photo.previewKey());
            if (photo.displayKey() != null && !photo.displayKey().isBlank()) {
                keys.add(photo.displayKey());
            }
        }
        return keys;
    }

    private void cleanupFailure(Long tripId, Long userId, String executionId,
                                List<String> originalsKeys, List<DerivedPhotoKeys> derivedKeys,
                                List<StoredFile> originalFiles, List<TripAttachment> savedAttachments,
                                RuntimeException failure) {
        List<String> keys = objectKeys(originalsKeys, derivedKeys);
        try {
            transactions.failAndDeleteReference(tripId, userId, executionId,
                    originalFiles.stream().map(StoredFile::getId).toList(),
                    savedAttachments.stream().map(TripAttachment::getId).toList());
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        for (String key : keys) delete(key, failure);
    }

    private void delete(String objectKey, RuntimeException failure) {
        try {
            storage.delete(objectKey);
        }
        catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
    }

    private List<String> validate(List<MultipartFile> files) {
        if (files == null || files.isEmpty() || files.stream().anyMatch(f -> f == null || f.isEmpty())) {
            throw new InvalidAttachmentUploadException();
        }
        if (files.size() > 10) throw new AttachmentUploadLimitExceededException();

        long total = 0;
        List<String> types = new ArrayList<>(files.size());

        for (MultipartFile file : files) {
            if (file.getSize() > 15L * 1024 * 1024) throw new AttachmentUploadLimitExceededException();
            total += file.getSize();
            if (total > 145L * 1024 * 1024) throw new AttachmentUploadLimitExceededException();

            String name = file.getOriginalFilename();
            if (name == null || name.isBlank() || name.length() > 255) throw new InvalidAttachmentUploadException();

            types.add(detectType(file));
        }
        return List.copyOf(types);
    }

    private String detectType(MultipartFile file) {
        byte[] h;
        try (InputStream input = file.getInputStream()) {
            h = input.readNBytes(12);
        } catch (IOException e) {
            throw new InvalidAttachmentUploadException();
        }

        if (h.length >= 3 && (h[0] & 255) == 255 && (h[1] & 255) == 216 && (h[2] & 255) == 255) return "image/jpeg";
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        if (h.length >= 8 && Arrays.equals(h, 0, 8, png, 0, 8)) return "image/png";
        if (h.length >= 12 && Arrays.equals(h, 4, 8, "ftyp".getBytes(StandardCharsets.US_ASCII), 0, 4)
                && Set.of("heic", "heix", "heim", "heis").contains(new String(h, 8, 4, StandardCharsets.US_ASCII))) {
            return "image/heic";
        }
        throw new UnsupportedAttachmentFormatException();
    }

    private TripPhotoAnalysisRequest analysisRequest(
            String executionId,
            Trip trip,
            List<TripAttachment> saved,
            List<DerivedPhotoKeys> derived
    ) {
        List<TripPhotoAnalysisRequest.Region> coordinates = regions.findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(trip.getId())
                .stream()
                .map(r -> new TripPhotoAnalysisRequest.Region(r.getLatitude(), r.getLongitude()))
                .toList();

        if (coordinates.isEmpty()) throw new IllegalStateException(TripInternalErrorMessage.TRIP_REGION_MISSING.message());

        List<TripPhotoAnalysisRequest.Photo> photos = new ArrayList<>(saved.size());
        for (int i = 0; i < saved.size(); i++) {
            TripAttachment photo = saved.get(i);
            DerivedPhotoKeys metadata = derived.get(i);
            photos.add(
                    new TripPhotoAnalysisRequest.Photo(
                            photo.getId(),
                            photo.getAnalyzeStorageKey(),
                            metadata.takenAt(),
                            metadata.latitude(),
                            metadata.longitude(),
                            metadata.deviceModel()
                    )
            );
        }

        return new TripPhotoAnalysisRequest(
                executionId,
                trip.getTripName(),
                new TripPhotoAnalysisRequest.Period(
                        trip.getStartDate(),
                        trip.getEndDate()
                ),
                coordinates,
                photos
        );
    }
}
