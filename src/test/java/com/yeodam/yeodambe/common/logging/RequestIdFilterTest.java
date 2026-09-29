package com.yeodam.yeodambe.common.logging;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 전달받은_요청_ID를_MDC와_응답_헤더에_유지한다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-ID", "request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdDuringProcessing = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                requestIdDuringProcessing.set(MDC.get("request_id"))
        );

        assertThat(requestIdDuringProcessing.get()).isEqualTo("request-123");
        assertThat(response.getHeader("X-Request-ID")).isEqualTo("request-123");
        assertThat(MDC.get("request_id")).isNull();
    }

    @Test
    void 요청_ID가_없으면_UUID를_새로_만든다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdDuringProcessing = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                requestIdDuringProcessing.set(MDC.get("request_id"))
        );

        String responseRequestId = response.getHeader("X-Request-ID");
        assertThat(UUID.fromString(responseRequestId)).isNotNull();
        assertThat(requestIdDuringProcessing.get()).isEqualTo(responseRequestId);
        assertThat(MDC.get("request_id")).isNull();
    }

    @Test
    void 형식이_잘못된_요청_ID는_UUID로_교체한다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-ID", "invalid request id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdDuringProcessing = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                requestIdDuringProcessing.set(MDC.get("request_id"))
        );

        String responseRequestId = response.getHeader("X-Request-ID");
        assertThat(UUID.fromString(responseRequestId)).isNotNull();
        assertThat(requestIdDuringProcessing.get()).isEqualTo(responseRequestId);
        assertThat(MDC.get("request_id")).isNull();
    }

    @Test
    void 후속_처리에서_예외가_나도_MDC를_비운다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-ID", "request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            assertThat(MDC.get("request_id")).isEqualTo("request-123");
            throw new ServletException("처리 실패");
        })).isInstanceOf(ServletException.class);

        assertThat(MDC.get("request_id")).isNull();
    }
}
