package com.yeodam.yeodambe.trip.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name="migration.service-schedulers-enabled", havingValue="true", matchIfMissing=true)
@RequiredArgsConstructor
public class TripObjectCleanupScheduler {
    private final TripObjectCleanupService cleanup;

    @Scheduled(fixedDelay = 60_000)
    void retryPending() {
        cleanup.retryPending();
    }
}
