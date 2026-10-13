package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripUploadTimeoutService {
    private final InitialUploadExecutionRegistry executions;
    private final TripRepository trips;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final TripCreationExecutionRepository creationExecutions;
    private final InitialAttachmentUploadTransactionService uploadTransactions;
    private final TripPhotoAnalysisService analysis;

    @Scheduled(fixedDelay = 30_000)
    void failExpiredUploads() {
        LocalDateTime now = uploadTransactions.databaseTime();
        var page = PageRequest.of(0, 100);
        creationExecutions.findUploadExpired(now.minusMinutes(10), page)
                .forEach(execution -> uploadTransactions.failExpiredExecution(execution.getExecutionId(), false));
        creationExecutions.findResultExpired(now.minusHours(1), page)
                .forEach(execution -> uploadTransactions.failExpiredExecution(execution.getExecutionId(), true));
        for (var execution : creationExecutions.findByCancelPendingTrue(page)) {
            try {
                analysis.cancel(execution.getTripId());
                uploadTransactions.recordCancellationDelivered(execution.getExecutionId());
            } catch (RuntimeException failure) {
                log.warn("AI 취소 전달 재시도: executionId={}, cause={}", execution.getExecutionId(),
                        failure.getClass().getSimpleName());
            }
        }
        executions.expireWaiting(clock.instant().minus(Duration.ofMinutes(10)), (tripId, snapshot) -> {
            try {
                transactions.executeWithoutResult(status -> trips.findById(tripId).ifPresent(trip -> {
                    int changed = trips.finishInitialUpload(tripId, trip.getUserId(),
                            ProcessingStatus.PROCESSING, ProcessingStatus.FAILED);
                    if (changed == 1) {
                        log.info("다음 사진 배치 대기시간 초과: tripId={}, executionId={}", tripId, snapshot.executionId());
                    }
                }));
                return true;
            } catch (RuntimeException failure) {
                log.warn("사진 업로드 대기 만료 처리 실패: tripId={}, executionId={}", tripId, snapshot.executionId(), failure);
                return false;
            }
        });
    }
}
