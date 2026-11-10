package com.yeodam.yeodambe.trip.event.handler;

import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEvent;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTarget;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTargetType;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventType;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;
import com.yeodam.yeodambe.trip.entity.TripDisplayStatus;
import com.yeodam.yeodambe.trip.event.TripProcessingStatusChangedEvent;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStateSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusNotification;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.yeodam.yeodambe.trip.service.TripEditNotificationOutboxService;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TripProcessingStatusEventHandlerTest {
    private final TripEditNotificationOutboxService publisher = mock(TripEditNotificationOutboxService.class);
    private final TripProcessingStatusEventHandler handler = new TripProcessingStatusEventHandler(publisher, Clock.systemUTC());

    @Test
    void 저장된_실패_코드와_기존_사용자_메시지를_포함한다() {
        TripProcessingStateSnapshot snapshot = new TripProcessingStateSnapshot(
                7L, "execution", 3, TripDisplayStatus.FAILED, null, "AI_PROCESSING_FAILED");

        handler.handle(new TripProcessingStatusChangedEvent(42L, snapshot));

        TripProcessingStatusNotification notification = notification();
        assertThat(notification.status()).isEqualTo(TripProcessingStatusResponse.Status.FAILED);
        assertThat(notification.statusVersion()).isEqualTo(3);
        assertThat(notification.error().code()).isEqualTo("AI_PROCESSING_FAILED");
        assertThat(notification.error().message()).isEqualTo("첨부 처리에 실패했습니다.");
        assertThat(notification.result()).isNull();
    }

    @Test
    void 취소_알림에는_실패를_첨부하지_않는다() {
        handler.handle(new TripProcessingStatusChangedEvent(42L, new TripProcessingStateSnapshot(
                7L, "execution", 3, TripDisplayStatus.CANCELED, null, null)));

        TripProcessingStatusNotification notification = notification();
        assertThat(notification.status()).isEqualTo(TripProcessingStatusResponse.Status.CANCELED);
        assertThat(notification.error()).isNull();
        assertThat(notification.result()).isNull();
    }

    @Test
    void 분석_저장_보류_상태는_사용자와_여행_참여자에게_같은_실행_정보로_저장한다() {
        String blockedReason = ErrorMessage.RESULT_APPLY_RETRY_EXHAUSTED.name();
        TripProcessingStateSnapshot snapshot = new TripProcessingStateSnapshot(
                7L, "addition", 5, TripDisplayStatus.FINALIZING, null, null, null,
                "analysis", TripAnalysisExecution.Status.APPLY_BLOCKED, blockedReason);

        handler.handle(new TripProcessingStatusChangedEvent(42L, snapshot));

        ArgumentCaptor<WebSocketEvent> captor = ArgumentCaptor.forClass(WebSocketEvent.class);
        verify(publisher, times(2)).enqueue(captor.capture(), isNull());
        assertThat(captor.getAllValues()).extracting(WebSocketEvent::type)
                .containsExactly(WebSocketEventType.AI_TASK, WebSocketEventType.TRIP_EVENT);
        assertThat(captor.getAllValues()).extracting(WebSocketEvent::target).containsExactly(
                new WebSocketEventTarget(WebSocketEventTargetType.USER, 42L),
                new WebSocketEventTarget(WebSocketEventTargetType.TRIP, 7L));
        for (WebSocketEvent event : captor.getAllValues()) {
            TripProcessingStatusNotification notification = (TripProcessingStatusNotification) event.payload().data();
            assertThat(notification.executionId()).isEqualTo("addition");
            assertThat(notification.statusVersion()).isEqualTo(5);
            assertThat(notification.analysisExecutionId()).isEqualTo("analysis");
            assertThat(notification.analysisState()).isEqualTo(TripAnalysisExecution.Status.APPLY_BLOCKED);
            assertThat(notification.blockedReason()).isEqualTo(blockedReason);
            assertThat(notification.status()).isEqualTo(TripProcessingStatusResponse.Status.FINALIZING);
            assertThat(notification.error()).isNull();
        }
    }

    private TripProcessingStatusNotification notification() {
        ArgumentCaptor<WebSocketEvent> captor = ArgumentCaptor.forClass(WebSocketEvent.class);
        verify(publisher).enqueue(captor.capture(), isNull());
        return (TripProcessingStatusNotification) captor.getValue().payload().data();
    }
}
