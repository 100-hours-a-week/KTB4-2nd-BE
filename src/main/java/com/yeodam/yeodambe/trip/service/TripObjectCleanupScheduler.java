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
}
