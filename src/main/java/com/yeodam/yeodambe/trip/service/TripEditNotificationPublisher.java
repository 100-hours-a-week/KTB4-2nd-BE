package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.websocket.service.WebSocketEventPublisher;
import com.yeodam.yeodambe.trip.entity.TripEditNotificationOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(propagation = Propagation.NEVER)
public class TripEditNotificationPublisher {

    private final TripEditNotificationPersistenceService persistence;
    private final WebSocketEventPublisher eventPublisher;
    private final Clock clock;

    @Scheduled(fixedDelay = 1_000)
    public void retryPending() {
        List<Long> ids;
        try {
            ids = persistence.findDueIds(now());
        } catch (RuntimeException exception) {
            logFailure("load_batch", null, exception);
            return;
        }
        for (Long id : ids) {
            publish(id);
        }
    }

    public void publish(Long id) {
        Optional<TripEditNotificationOutbox> pending;
        try {
            pending = persistence.findDue(id, now());
        } catch (RuntimeException exception) {
            logFailure("load", id, exception);
            return;
        }
        if (pending.isEmpty()) {
            return;
        }
        TripEditNotificationOutbox notification = pending.orElseThrow();
        try {
            eventPublisher.publishSerialized(notification.getEventJson());
        } catch (RuntimeException exception) {
            logFailure("publish", id, exception);
            recordFailure(notification);
            return;
        }
        try {
            persistence.markPublished(id, now());
        } catch (RuntimeException exception) {
            logFailure("record_success", id, exception);
        }
    }

    private void recordFailure(TripEditNotificationOutbox notification) {
        try {
            persistence.markFailed(notification, now());
        } catch (RuntimeException exception) {
            logFailure("record_failure", notification.getId(), exception);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private void logFailure(String stage, Long id, RuntimeException exception) {
        log.atWarn().addKeyValue("event", "trip_edit_notification")
                .addKeyValue("notification_id", id)
                .addKeyValue("failure_stage", stage)
                .addKeyValue("failure", exception.getClass().getName())
                .log("공유 편집 알림 발행 실패. 미발행 기록을 보존합니다.");
    }
}
