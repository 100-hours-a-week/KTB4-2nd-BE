package com.yeodam.yeodambe.story.event.handler;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.common.websocket.service.WebSocketEventPublisher;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEvent;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTarget;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTargetType;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventType;
import com.yeodam.yeodambe.story.event.StoryGenerationStatusChangedEvent;
import com.yeodam.yeodambe.story.service.response.StoryGenerationStatusNotification;
import com.yeodam.yeodambe.common.websocket.service.request.AiTaskCompletionType;
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
public class StoryGenerationStatusEventHandler {

    private final WebSocketEventPublisher eventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(StoryGenerationStatusChangedEvent event) {
        var snapshot = event.snapshot();
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now();

        var notification = new StoryGenerationStatusNotification(
                eventId,
                occurredAt,
                event.tripId(),
                snapshot.generationId(),
                snapshot.statusVersion(),
                snapshot.status(),
                snapshot.storyId(),
                snapshot.error(),
                event.listItem() == null ? null : AiTaskCompletionType.STORY_GENERATION_COMPLETED,
                event.listItem()
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
                        SuccessMessage.STORY_GENERATION_STATUS_FOUND,
                        notification
                )
        );

        try {
            eventPublisher.publish(message);
        } catch (RuntimeException failure) {
            log.atWarn()
                    .addKeyValue("event", "story_status_notification")
                    .addKeyValue("result", "failure")
                    .addKeyValue("trip_id", event.tripId())
                    .addKeyValue("job_id", snapshot.generationId())
                    .addKeyValue("failure", failure.getClass().getName())
                    .log("스토리 처리 상태 알림 발행에 실패했습니다.");
        }
    }
}
