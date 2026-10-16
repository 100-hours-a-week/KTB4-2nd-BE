package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripUploadTimeoutService {
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
    }
}
