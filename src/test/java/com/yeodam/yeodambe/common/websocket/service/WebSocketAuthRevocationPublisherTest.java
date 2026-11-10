package com.yeodam.yeodambe.common.websocket.service;

import com.yeodam.yeodambe.common.websocket.service.request.WebSocketAuthRevocationData;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketAuthRevocationScope;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEvent;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTargetType;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.then;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class WebSocketAuthRevocationPublisherTest {

    private final WebSocketEventPublisher eventPublisher = mock(WebSocketEventPublisher.class);
    private final WebSocketAuthRevocationPublisher publisher =
            new WebSocketAuthRevocationPublisher(eventPublisher);

    @Test
    void 로그아웃은_해당_사용자의_로그인_세션만_알린다() {
        publisher.revokeSession(42L, "sid-123");

        ArgumentCaptor<WebSocketEvent> event = ArgumentCaptor.forClass(WebSocketEvent.class);
        then(eventPublisher).should().publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo(WebSocketEventType.AUTH_REVOKED);
        assertThat(event.getValue().target().type()).isEqualTo(WebSocketEventTargetType.USER);
        assertThat(event.getValue().target().id()).isEqualTo(42L);
        assertThat(event.getValue().payload().message()).isEqualTo("AUTH_REVOKED");
        assertThat(event.getValue().payload().data()).isEqualTo(
                new WebSocketAuthRevocationData(WebSocketAuthRevocationScope.SESSION, "sid-123")
        );
    }

    @Test
    void 회원_탈퇴는_사용자의_모든_로그인_세션을_알린다() {
        publisher.revokeUser(42L);

        ArgumentCaptor<WebSocketEvent> event = ArgumentCaptor.forClass(WebSocketEvent.class);
        then(eventPublisher).should().publish(event.capture());
        assertThat(event.getValue().target().id()).isEqualTo(42L);
        assertThat(event.getValue().payload().data()).isEqualTo(
                new WebSocketAuthRevocationData(WebSocketAuthRevocationScope.USER, null)
        );
    }

    @Test
    void Redis_발행_실패가_로그아웃이나_회원_탈퇴를_실패시키지_않는다() {
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(eventPublisher).publish(any());

        assertThatCode(() -> publisher.revokeSession(42L, "sid-123"))
                .doesNotThrowAnyException();
    }
}
