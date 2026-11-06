package com.yeodam.yeodambe.trip.event.handler;

import com.yeodam.yeodambe.common.websocket.service.WebSocketEventPublisher;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEvent;
import com.yeodam.yeodambe.trip.entity.TripDisplayStatus;
import com.yeodam.yeodambe.trip.event.TripProcessingStatusChangedEvent;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStateSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusNotification;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TripProcessingStatusEventHandlerTest {
    private final WebSocketEventPublisher publisher = mock(WebSocketEventPublisher.class);
    private final TripProcessingStatusEventHandler handler = new TripProcessingStatusEventHandler(publisher, mock(com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient.class));

    @Test
    void 저장된_실패_코드와_기존_사용자_메시지를_포함한다() {
        var snapshot = new TripProcessingStateSnapshot(
                7L, "execution", 3, TripDisplayStatus.FAILED, null, "AI_PROCESSING_FAILED");

        handler.handle(new TripProcessingStatusChangedEvent(42L, snapshot));

        var notification = notification();
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

        var notification = notification();
        assertThat(notification.status()).isEqualTo(TripProcessingStatusResponse.Status.CANCELED);
        assertThat(notification.error()).isNull();
        assertThat(notification.result()).isNull();
    }

    private TripProcessingStatusNotification notification() {
        var captor = ArgumentCaptor.forClass(WebSocketEvent.class);
        verify(publisher).publish(captor.capture());
        return (TripProcessingStatusNotification) captor.getValue().payload().data();
    }
}
