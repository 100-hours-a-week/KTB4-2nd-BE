package com.yeodam.yeodambe.trip.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TripObjectCleanupScheduler {
    private final TripObjectCleanupService cleanup;

    @Scheduled(fixedDelay = 60_000)
    void retryPending() {
        cleanup.retryPending();
    }

    @Scheduled(fixedDelay = 60_000)
    void processScheduled() {
        cleanup.processScheduled();
    }

    @Scheduled(initialDelay = 0, fixedDelay = 3_600_000)
    void recoverSchedules() {
        cleanup.recoverSchedules();
    }
}
