package com.yeodam.yeodambe.trip.event.handler;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import com.yeodam.yeodambe.trip.service.TripEditNotificationOutboxService;
import com.yeodam.yeodambe.trip.service.response.TripCompletionSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStateSnapshot;

import java.time.Instant;
import java.util.UUID;
import java.time.Clock;

@Component
@RequiredArgsConstructor
public class TripProcessingStatusEventHandler {
    private final TripEditNotificationOutboxService notifications;
    private final Clock clock;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handle(TripProcessingStatusChangedEvent event) {
        queue(event, WebSocketEventType.AI_TASK,
                new WebSocketEventTarget(WebSocketEventTargetType.USER, event.userId()));
        if (event.snapshot().notifyParticipants()) {
            queue(event, WebSocketEventType.TRIP_EVENT,
                    new WebSocketEventTarget(WebSocketEventTargetType.TRIP, event.snapshot().tripId()));
        }
    }

    private void queue(TripProcessingStatusChangedEvent event, WebSocketEventType type, WebSocketEventTarget target) {
        TripProcessingStateSnapshot snapshot = event.snapshot();
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = clock.instant();
        TripProcessingStatusResponse.Error error = snapshot.displayStatus() == TripDisplayStatus.FAILED
                ? new TripProcessingStatusResponse.Error(snapshot.errorCode(),
                        TripInternalErrorMessage.ATTACHMENT_PROCESSING_FAILED.message()) : null;
        TripCompletionSnapshot completion = event.completion();
        TripProcessingStatusNotification notification = new TripProcessingStatusNotification(
                eventId, occurredAt, snapshot.tripId(), snapshot.executionId(), snapshot.statusVersion(),
                TripProcessingStatusResponse.Status.valueOf(snapshot.displayStatus().name()), snapshot.currentStep(),
                completion == null ? null : completion.result(), error,
                completion == null ? null : completion.eventType(), completion == null ? null : completion.listItem(),
                snapshot.analysisExecutionId(), snapshot.analysisState(), snapshot.blockedReason());
        notifications.enqueue(new WebSocketEvent(eventId, type, 1, occurredAt, target,
                new ApiResponse<>(SuccessMessage.TRIP_PROCESSING_STATUS_FOUND, notification)),
                completion == null ? null : completion.thumbnailKey());
    }
}
