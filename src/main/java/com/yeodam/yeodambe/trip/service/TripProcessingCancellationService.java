package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.TripProcessingCannotBeCanceledException;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripProcessingCancellationService {
    private final TripRepository trips;
    private final TripRegionRepository regions;
    private final TripDetailPlaceRepository places;
    private final TripAttachmentRepository attachments;
    private final StoredFileRepository files;
    private final TripPhotoAnalysisService analysis;
    private final InitialUploadExecutionRegistry executions;
    private final TransactionOperations transactions;
    private final InitialAttachmentUploadBatchRepository uploadBatches;
    private final TripAttachmentDerivativeService derivatives;
    private final InitialAttachmentUploadTransactionService uploadTransactions;
    private final TripCreationExecutionRepository creationExecutions;

    public void cancel(
            Long tripId,
            Long userId
    ) {
        transactions.executeWithoutResult(status -> cancelInTransaction(tripId, userId));

        var persistent = creationExecutions.findByTripId(tripId);
        if (persistent.isPresent()) {
            var execution = persistent.get();
            derivatives.cancelExecution(execution.getExecutionId());
            if (execution.isCancelPending()) {
                try {
                    analysis.cancel(tripId);
                    uploadTransactions.recordCancellationDelivered(execution.getExecutionId());
                } catch (RuntimeException failure) {
                    log.warn("AI 사진 분석 취소 전달 재시도: executionId={}, cause={}",
                            execution.getExecutionId(), failure.getClass().getSimpleName());
                }
            }
        }
        boolean legacyAnalysisStarted = executions.cancel(tripId);
        String executionId = null;
        try {
            executionId = executions.snapshot(tripId).executionId();
        } catch (TripInitialAttachmentUploadNotAllowedException absent) {
            // 재시작 또는 이미 정착한 실행은 메모리 레지스트리에 없다.
        }
        if (executionId != null) {
            derivatives.cancelExecution(executionId);
        }
        if (legacyAnalysisStarted || uploadBatches.existsByTripIdAndStatus(
                tripId, InitialAttachmentUploadStatus.ANALYZING)) cancelAnalysis(tripId);
    }

    private void cancelInTransaction(
            Long tripId,
            Long userId
    ) {
        LocalDateTime canceledAt = LocalDateTime.now();
        if (trips.cancelProcessing(
                tripId, userId, ProcessingStatus.PROCESSING, ProcessingStatus.CANCELED, canceledAt) != 1) {
            throw cancellationFailure(tripId, userId);
        }

        uploadTransactions.cancelExecution(tripId, userId);
        List<TripAttachment> tripAttachments = attachments.findAllByTripIdAndDeletedAtIsNull(tripId);
        List<Long> fileIds = tripAttachments.stream()
                .map(TripAttachment::getFileId)
                .toList();
        regions.softDeleteByTripId(tripId, canceledAt);
        places.softDeleteByTripId(tripId, canceledAt);
        attachments.softDeleteByTripId(tripId, canceledAt);

        if (!fileIds.isEmpty()) {
            files.softDeleteByIds(fileIds, canceledAt);
        }

    }

    private RuntimeException cancellationFailure(
            Long tripId,
            Long userId
    ) {
        Trip trip = trips.findByIdAndUserId(tripId, userId)
                .orElseThrow(TripNotFoundException::new);

        if (trip.getProcessingStatus() == ProcessingStatus.CANCELED) {
            return new TripProcessingCannotBeCanceledException();
        }

        if (trip.getDeletedAt() != null) {
            return new TripNotFoundException();
        }

        return new TripProcessingCannotBeCanceledException();
    }

    private void cancelAnalysis(Long tripId) {
        try {
            analysis.cancel(tripId);
        } catch (RuntimeException failure) {
            log.warn("AI 사진 분석 취소에 실패했습니다. tripId={}, cause={}", tripId, failure.getClass().getSimpleName());
        }
    }

}
