package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class TripUploadTimeoutService {
    private final InitialUploadExecutionRegistry executions;
    private final TripRepository trips;
    private final TransactionOperations transactions;
    private final Clock clock;

    @Scheduled(fixedDelay = 30_000)
    void failExpiredUploads() {
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
