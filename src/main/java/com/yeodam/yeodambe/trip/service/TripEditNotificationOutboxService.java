package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.websocket.service.WebSocketEventPublisher;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEvent;
import com.yeodam.yeodambe.trip.entity.TripEditNotificationOutbox;
import com.yeodam.yeodambe.trip.repository.TripEditNotificationOutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
@Slf4j
public class TripEditNotificationOutboxService {

    private final TripEditNotificationOutboxRepository records;
    private final WebSocketEventPublisher eventPublisher;
    private final ApplicationEventPublisher applicationEvents;
    private final TripEditNotificationPublisher publisher;
    private final Executor executor;
    private final Clock clock;

    public TripEditNotificationOutboxService(
            TripEditNotificationOutboxRepository records,
            WebSocketEventPublisher eventPublisher,
            ApplicationEventPublisher applicationEvents,
            TripEditNotificationPublisher publisher,
            @Qualifier("tripEditNotificationExecutor") Executor executor,
            Clock clock
    ) {
        this.records = records;
        this.eventPublisher = eventPublisher;
        this.applicationEvents = applicationEvents;
        this.publisher = publisher;
        this.executor = executor;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(WebSocketEvent event) {
        TripEditNotificationOutbox notification;
        try {
            String eventJson = eventPublisher.serialize(event);
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            notification = records.saveAndFlush(new TripEditNotificationOutbox(event, eventJson, now));
        } catch (RuntimeException exception) {
            log.error("공유 편집 알림 기록 실패: failure={}", exception.getClass().getSimpleName());
            throw exception;
        }
        applicationEvents.publishEvent(new NotificationQueued(notification.getId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(NotificationQueued event) {
        try {
            executor.execute(() -> publisher.publish(event.id()));
        } catch (RejectedExecutionException exception) {
            log.warn("공유 편집 알림 비동기 접수 실패: notificationId={}, failure={}",
                    event.id(), exception.getClass().getSimpleName());
        }
    }

    public record NotificationQueued(
            Long id
    ) {
    }
}
