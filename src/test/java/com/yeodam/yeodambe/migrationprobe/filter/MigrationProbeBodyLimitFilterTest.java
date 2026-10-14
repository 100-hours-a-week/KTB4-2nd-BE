package com.yeodam.yeodambe.migrationprobe.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationProbeBodyLimitFilterTest {

    private final MigrationProbeBodyLimitFilter filter = new MigrationProbeBodyLimitFilter(new ObjectMapper());

    @Test
    void missingLengthAndChunkedBodyCannotBypassLimit() throws Exception {
        var request = request(-1, "a".repeat(1025));
        request.addHeader("Transfer-Encoding", "chunked");
        var response = new MockHttpServletResponse();
        AtomicBoolean forwarded = new AtomicBoolean();

        filter.doFilter(request, response, (incoming, outgoing) -> forwarded.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(forwarded).isFalse();
    }

    @Test
    void falseShortLengthCannotBypassActualByteLimit() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request(1, "a".repeat(1025)), response, (incoming, outgoing) -> {
            throw new AssertionError("큰 본문이 전달되었습니다.");
        });
        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void utf8BodyIsMeasuredInBytes() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request(-1, "가".repeat(342)), response, (incoming, outgoing) -> {
            throw new AssertionError("UTF-8 제한을 초과한 본문이 전달되었습니다.");
        });
        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void exactByteLimitIsReplayable() throws Exception {
        String body = "a".repeat(1024);
        var response = new MockHttpServletResponse();
        filter.doFilter(request(-1, body), response, (incoming, outgoing) -> {
            assertThat(incoming.getInputStream().readAllBytes()).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
            assertThat(incoming.getReader().readLine()).isEqualTo(body);
        });
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void unrelatedRouteAndMethodKeepExistingBodyBehavior() throws Exception {
        var request = request(-1, "a".repeat(2048));
        request.setServletPath("/trips");
        filter.doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) ->
                assertThat(incoming.getInputStream().readAllBytes()).hasSize(2048));

        var getRequest = request(-1, "a".repeat(2048));
        getRequest.setMethod("GET");
        filter.doFilter(getRequest, new MockHttpServletResponse(), (incoming, outgoing) ->
                assertThat(incoming.getInputStream().readAllBytes()).hasSize(2048));
    }

    private MockHttpServletRequest request(long length, String body) {
        var request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return length;
            }
        };
        request.setMethod("POST");
        request.setServletPath("/internal/migration-write-probe");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
