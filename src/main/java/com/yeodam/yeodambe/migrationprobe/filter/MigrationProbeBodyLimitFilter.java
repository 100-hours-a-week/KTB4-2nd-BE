package com.yeodam.yeodambe.migrationprobe.filter;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@Profile("staging | prod")
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class MigrationProbeBodyLimitFilter extends OncePerRequestFilter {

    private static final int MAX_BODY_BYTES = 1024;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !"/internal/migration-write-probe".equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            rejectOversizedBody(response);
            return;
        }

        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            rejectOversizedBody(response);
            return;
        }

        chain.doFilter(new BufferedBodyRequest(request, body), response);
        if (response.getStatus() == HttpServletResponse.SC_BAD_REQUEST) {
            logRejection("validation", ErrorMessage.INVALID_REQUEST);
        }
    }

    private void rejectOversizedBody(HttpServletResponse response) throws IOException {
        logRejection("body_limit", ErrorMessage.MIGRATION_WRITE_PROBE_BODY_TOO_LARGE);
        response.setStatus(413);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new ApiResponse<>(ErrorMessage.MIGRATION_WRITE_PROBE_BODY_TOO_LARGE, null));
    }

    private void logRejection(String stage, ErrorMessage message) {
        log.atWarn().addKeyValue("event", "migration_write_probe")
                .addKeyValue("result", "failure")
                .addKeyValue("failure_stage", stage)
                .addKeyValue("commit_result", "not_attempted")
                .addKeyValue("error_code", message.name())
                .log("DB 전환 시험 요청을 거절했습니다.");
    }

    private static class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new BufferedBodyInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    private static class BufferedBodyInputStream extends ServletInputStream {

        private final ByteArrayInputStream input;

        private BufferedBodyInputStream(byte[] body) {
            input = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return input.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return input.read(bytes, offset, length);
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new IllegalStateException("시험 API는 동기 본문 읽기만 지원합니다.");
        }
    }
}
