package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.websocket.service.WebSocketEventPublisher;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEvent;
import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTarget;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTargetType;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventType;
import com.yeodam.yeodambe.trip.entity.TripEditNotificationOutbox;
import com.yeodam.yeodambe.trip.repository.TripEditNotificationOutboxRepository;
import com.yeodam.yeodambe.trip.service.request.TripUpdateRequest;
import com.yeodam.yeodambe.trip.service.response.TripUpdateResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
    public void enqueueTripUpdated(
            Long tripId,
            long sequence,
            String requestId,
            TripUpdateRequest changes,
            TripUpdateResponse updated
    ) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = clock.instant();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventId", eventId);
        data.put("requestId", requestId);
        data.put("tripId", tripId);
        data.put("sequence", sequence);
        data.put("occurredAt", occurredAt);
        Map<String, Object> changed = new LinkedHashMap<>();
        if (changes.tripName() != null) {
            changed.put("tripName", updated.tripName());
        }
        if (changes.startDate() != null || changes.endDate() != null) {
            changed.put("startDate", updated.startDate());
            changed.put("endDate", updated.endDate());
        }
        if (changes.regionCodes() != null) {
            changed.put("regionCodes", updated.regions().stream()
                    .map(TripUpdateResponse.Region::regionCode)
                    .toList());
            changed.put("regions", updated.regions());
        }
        data.put("changes", changed);
        enqueue(new WebSocketEvent(
                eventId,
                WebSocketEventType.TRIP_EVENT,
                1,
                occurredAt,
                new WebSocketEventTarget(WebSocketEventTargetType.TRIP, tripId),
                new ApiResponse<>(SuccessMessage.TRIP_UPDATED, data)
        ));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(WebSocketEvent event) {
        enqueue(event, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(WebSocketEvent event, String thumbnailKey) {
        TripEditNotificationOutbox notification;
        try {
            String eventJson = eventPublisher.serialize(event);
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            notification = records.saveAndFlush(new TripEditNotificationOutbox(event, eventJson, now, thumbnailKey));
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
