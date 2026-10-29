package com.yeodam.yeodambe.trip.event.handler;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.common.websocket.service.WebSocketEventPublisher;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEvent;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTarget;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTargetType;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventType;
import com.yeodam.yeodambe.trip.event.TripProcessingStatusChangedEvent;
import com.yeodam.yeodambe.trip.entity.TripDisplayStatus;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusNotification;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TripProcessingStatusEventHandler {

    private final WebSocketEventPublisher eventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(TripProcessingStatusChangedEvent event) {
        var snapshot = event.snapshot();
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now();

        TripProcessingStatusResponse.Error error = null;
        if (snapshot.displayStatus() == TripDisplayStatus.FAILED) {
            error = new TripProcessingStatusResponse.Error(
                    snapshot.errorCode(),
                    TripInternalErrorMessage.ATTACHMENT_PROCESSING_FAILED.message());
        }

        var notification = new TripProcessingStatusNotification(
                eventId,
                occurredAt,
                snapshot.tripId(),
                snapshot.executionId(),
                snapshot.statusVersion(),
                TripProcessingStatusResponse.Status.valueOf(
                        snapshot.displayStatus().name()
                ),
                snapshot.currentStep(),
                null,
                error
        );

        var message = new WebSocketEvent(
                eventId,
                WebSocketEventType.AI_TASK,
                1,
                occurredAt,
                new WebSocketEventTarget(
                        WebSocketEventTargetType.USER,
                        event.userId()
                ),
                new ApiResponse<>(
                        SuccessMessage.TRIP_PROCESSING_STATUS_FOUND,
                        notification
                )
        );

        try {
            eventPublisher.publish(message);
        } catch (RuntimeException failure) {
            log.atWarn()
                    .addKeyValue("event", "trip_status_notification")
                    .addKeyValue("result", "failure")
                    .addKeyValue("trip_id", snapshot.tripId())
                    .addKeyValue("job_id", snapshot.executionId())
                    .addKeyValue("failure", failure.getClass().getName())
                    .log("여행 처리 상태 알림 발행에 실패했습니다.");
        }
    }
}
