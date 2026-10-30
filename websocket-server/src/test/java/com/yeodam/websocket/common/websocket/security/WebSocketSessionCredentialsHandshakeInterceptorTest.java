package com.yeodam.websocket.common.websocket.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WebSocketSessionCredentialsHandshakeInterceptorTest {

    private final WebSocketSessionCredentialsHandshakeInterceptor interceptor =
            new WebSocketSessionCredentialsHandshakeInterceptor();

    @Test
    void 자격_증명을_응답에_노출하지_않고_연결_속성으로_전달한다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        WebSocketSessionCredentials credentials = new WebSocketSessionCredentials("test-access", "test-context");
        request.setAttribute(WebSocketSessionCredentials.ATTRIBUTE_NAME, credentials);
        var attributes = new HashMap<String, Object>();

        boolean allowed = interceptor.beforeHandshake(
                new ServletServerHttpRequest(request), new ServletServerHttpResponse(response),
                new TextWebSocketHandler(), attributes
        );

        assertThat(allowed).isTrue();
        assertThat(attributes.get(WebSocketSessionCredentials.ATTRIBUTE_NAME)).isSameAs(credentials);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getCookies()).isEmpty();
    }

    @Test
    void 자격_증명이_없으면_거절한다() {
        assertRejected(new ServletServerHttpRequest(new MockHttpServletRequest()));
    }

    @Test
    void 예상하지_못한_속성_타입을_거절한다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(WebSocketSessionCredentials.ATTRIBUTE_NAME, "unexpected-value");
        assertRejected(new ServletServerHttpRequest(request));
    }

    @Test
    void 지원하지_않는_요청_타입을_거절한다() {
        assertRejected(mock(ServerHttpRequest.class));
    }

    private void assertRejected(ServerHttpRequest request) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        var attributes = new HashMap<String, Object>();

        boolean allowed = interceptor.beforeHandshake(
                request, new ServletServerHttpResponse(response), new TextWebSocketHandler(), attributes
        );

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(attributes).isEmpty();
    }
}
