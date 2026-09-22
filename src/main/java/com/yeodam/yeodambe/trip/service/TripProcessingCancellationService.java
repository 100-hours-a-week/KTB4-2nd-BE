package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.TripProcessingCannotBeCanceledException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripProcessingCancellationService {
    private final TripRepository trips;
    private final TripRegionRepository regions;
    private final TripDetailPlaceRepository places;
    private final TripAttachmentRepository attachments;
    private final StoredFileRepository files;
    private final TripAttachmentStorageClient storage;
    private final TripPhotoAnalysisService analysis;
    private final InitialUploadExecutionRegistry executions;
    private final TransactionOperations transactions;

    public void cancel(Long tripId, Long userId) {
        List<String> objectKeys = Objects.requireNonNull(
                transactions.execute(status -> cancelInTransaction(tripId, userId)));

        if (executions.cancel(tripId)) cancelAnalysis(tripId);

        objectKeys.forEach(key -> deleteObject(tripId, key));
    }

    private List<String> cancelInTransaction(Long tripId, Long userId) {
        LocalDateTime canceledAt = LocalDateTime.now();
        if (trips.cancelProcessing(
                tripId, userId, ProcessingStatus.PROCESSING, ProcessingStatus.CANCELED, canceledAt) != 1) {
            throw cancellationFailure(tripId, userId);
        }

        List<TripAttachment> tripAttachments = attachments.findAllByTripIdAndDeletedAtIsNull(tripId);
        List<Long> fileIds = tripAttachments.stream()
                .map(TripAttachment::getFileId)
                .toList();
        List<StoredFile> storedFiles = fileIds.isEmpty() ? List.of() : files.findAllById(fileIds);
        List<String> objectKeys = new ArrayList<>(storedFiles.stream()
                .map(StoredFile::getObjectKey)
                .toList());
        for (TripAttachment attachment : tripAttachments) {
            objectKeys.add(attachment.getAnalyzeStorageKey());
            objectKeys.add(attachment.getPreviewStorageKey());
        }

        regions.softDeleteByTripId(tripId, canceledAt);
        places.softDeleteByTripId(tripId, canceledAt);
        attachments.softDeleteByTripId(tripId, canceledAt);

        if (!fileIds.isEmpty()) files.softDeleteByIds(fileIds, canceledAt);

        return List.copyOf(objectKeys);
    }

    private RuntimeException cancellationFailure(Long tripId, Long userId) {
        Trip trip = trips.findByIdAndUserId(tripId, userId)
                .orElseThrow(TripNotFoundException::new);

        if (trip.getProcessingStatus() == ProcessingStatus.CANCELED) {
            return new TripProcessingCannotBeCanceledException();
        }

        if (trip.getDeletedAt() != null) return new TripNotFoundException();

        return new TripProcessingCannotBeCanceledException();
    }

    private void cancelAnalysis(Long tripId) {
        try {
            analysis.cancel(tripId);
        } catch (RuntimeException failure) {
            log.warn("AI 사진 분석 취소에 실패했습니다. tripId={}", tripId, failure);
        }
    }

    private void deleteObject(Long tripId, String objectKey) {
        try {
            storage.delete(objectKey);
        } catch (RuntimeException failure) {
            log.warn("취소된 여행의 S3 객체 삭제에 실패했습니다. tripId={}, objectKey={}",
                    tripId, objectKey, failure);
        }
    }
}
