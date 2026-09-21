package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.*;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.integration.service.request.TripPhotoAnalysisRequest;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TripAttachmentService {
    private final TripRepository trips;
    private final TripRegionRepository regions;
    private final TripAttachmentStorageClient storage;
    private final TripAttachmentTransactionService transactions;
    private final TripAttachmentDerivativeService derivatives;
    private final TripPhotoAnalysisService analysis;
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

        deleteStaleObjects(reservation.staleObjectKeys());

        List<String> originalsKeys = new ArrayList<>();
        List<StoredFile> originalFiles = List.of();
        List<DerivedPhotoKeys> derivedKeys = List.of();
        List<TripAttachment> savedAttachments = List.of();

        try {
            storeOriginals(executionId, files, originalsKeys);
            derivedKeys = createDerived(executionId, originalsKeys, files.size());

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

            requireProcessing(tripId, userId);
            JsonNode result = analysis.analyze(
                    tripId,
                    executionId,
                    analysisRequest(executionId, trip, savedAttachments, derivedKeys),
                    () -> executions.markAnalysisStarted(tripId, executionId)
            );

            storage.retain(List.copyOf(objectKeys(originalsKeys, derivedKeys)));
            results.saveCompleted(tripId, userId, executionId, savedAttachments, result);

        } catch (AiProcessingFailedException failure) {
            cleanupFailure(tripId, userId, executionId, originalsKeys, derivedKeys,
                    originalFiles, savedAttachments, failure);
            if (failure.getSuppressed().length > 0) throw failure;
            return new TripProcessingStatusResponse(
                    failure.getTripId(),
                    ProcessingStatus.FAILED,
                    new TripProcessingStatusResponse.Progress(failure.getDone(), failure.getTotal()),
                    failure.getCurrentStep(),
                    null,
                    new TripProcessingStatusResponse.Error(
                            failure.getCode(), failure.getPublicMessage())
            );

        } catch (RuntimeException failure) {
            cleanupFailure(tripId, userId, executionId, originalsKeys, derivedKeys,
                    originalFiles, savedAttachments, failure);
            throw failure;

        } finally {
            executions.release(tripId, executionId);
        }

        return statuses.findStatus(tripId, userId);
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
            if (key == null || key.isBlank()) throw new IllegalStateException("S3 객체 키가 없습니다.");
            originalsKeys.add(key);
        }
    }

    private List<DerivedPhotoKeys> createDerived(String executionId, List<String> originalsKeys, int count) {
        List<DerivedPhotoKeys> derived = derivatives.createAll(executionId, List.copyOf(originalsKeys)).join();
        if (derived == null || derived.size() != count) throw new IllegalStateException("파생 사진 수가 다릅니다.");

        for (int i = 0; i < derived.size(); i++) {
            DerivedPhotoKeys keys = derived.get(i);
            if (keys == null || !originalsKeys.get(i).equals(keys.originalKey())
                    || keys.analyzeKey() == null || keys.analyzeKey().isBlank()
                    || keys.previewKey() == null || keys.previewKey().isBlank()) {
                throw new IllegalStateException("파생 사진 결과가 올바르지 않습니다.");
            }
        }
        return derived;
    }

    private List<String> objectKeys(List<String> originalsKeys, List<DerivedPhotoKeys> derivedKeys) {
        List<String> keys = new ArrayList<>(originalsKeys);
        for (DerivedPhotoKeys photo : derivedKeys) {
            keys.add(photo.analyzeKey());
            keys.add(photo.previewKey());
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
        if (files.size() > 200) throw new AttachmentUploadLimitExceededException();

        long total = 0;
        List<String> types = new ArrayList<>(files.size());

        for (MultipartFile file : files) {
            if (file.getSize() > 15L * 1024 * 1024) throw new AttachmentUploadLimitExceededException();
            total += file.getSize();
            if (total > 3L * 1024 * 1024 * 1024) throw new AttachmentUploadLimitExceededException();

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

        if (coordinates.isEmpty()) throw new IllegalStateException("여행 지역이 없습니다.");

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
