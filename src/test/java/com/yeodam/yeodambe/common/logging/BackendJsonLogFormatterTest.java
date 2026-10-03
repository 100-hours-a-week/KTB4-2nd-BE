package com.yeodam.yeodambe.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BackendJsonLogFormatterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 공통_필드와_MDC_값을_JSON으로_출력한다() throws Exception {
        ThrowableProxyConverter throwableProxyConverter = new ThrowableProxyConverter();
        throwableProxyConverter.start();

        BackendJsonLogFormatter formatter = new BackendJsonLogFormatter(
                new MockEnvironment().withProperty("RELEASE", "sha-123"),
                throwableProxyConverter
        );

        LoggingEvent event = new LoggingEvent();
        event.setInstant(Instant.parse("2026-09-28T07:10:21.315Z"));
        event.setLevel(Level.ERROR);
        event.setLoggerName("com.yeodam.yeodambe.trip.service.BulkAttachmentDownloadService");
        event.setMessage("일괄 사진 다운로드에 실패했습니다.");
        event.setMDCPropertyMap(Map.of(
                "request_id", "request-123",
                "trip_id", "42",
                "job_id", "execution-123"
        ));
        event.setKeyValuePairs(List.of(
                new KeyValuePair("event", "attachment_bulk_download"),
                new KeyValuePair("result", "failure"),
                new KeyValuePair("duration_ms", 8241),
                new KeyValuePair("failure_stage", "archive_upload"),
                new KeyValuePair("error_code", "INTERNAL_SERVER_ERROR"),
                new KeyValuePair("expected_count", 5),
                new KeyValuePair("saved_count", 3),
                new KeyValuePair("worker", Map.of("stage", "put_preview", "photo_index", 1))
        ));
        event.setThrowableProxy(new ThrowableProxy(new IllegalStateException("S3 업로드 실패")));

        var json = objectMapper.readTree(formatter.format(event));

        assertThat(json.path("timestamp").asString()).isEqualTo("2026-09-28T07:10:21.315Z");
        assertThat(json.path("level").asString()).isEqualTo("ERROR");
        assertThat(json.path("worker").path("stage").asString()).isEqualTo("put_preview");
        assertThat(json.path("worker").path("photo_index").asInt()).isEqualTo(1);
        assertThat(json.path("service").asString()).isEqualTo("backend");
        assertThat(json.path("logger").asString())
                .isEqualTo("com.yeodam.yeodambe.trip.service.BulkAttachmentDownloadService");
        assertThat(json.path("request_id").asString()).isEqualTo("request-123");
        assertThat(json.path("trip_id").asLong()).isEqualTo(42L);
        assertThat(json.path("job_id").asString()).isEqualTo("execution-123");
        assertThat(json.path("event").asString()).isEqualTo("attachment_bulk_download");
        assertThat(json.path("result").asString()).isEqualTo("failure");
        assertThat(json.path("duration_ms").asLong()).isEqualTo(8241L);
        assertThat(json.path("failure_stage").asString()).isEqualTo("archive_upload");
        assertThat(json.path("error_code").asString()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(json.path("expected_count").asLong()).isEqualTo(5L);
        assertThat(json.path("saved_count").asLong()).isEqualTo(3L);
        assertThat(json.path("release").asString()).isEqualTo("sha-123");
        assertThat(json.path("stack_trace").asString()).contains("S3 업로드 실패");
    }

    @Test
    void 업무_필드가_없는_일반_로그도_JSON으로_출력한다() throws Exception {
        ThrowableProxyConverter throwableProxyConverter = new ThrowableProxyConverter();
        throwableProxyConverter.start();

        BackendJsonLogFormatter formatter = new BackendJsonLogFormatter(
                new MockEnvironment(),
                throwableProxyConverter
        );

        LoggingEvent event = new LoggingEvent();
        event.setInstant(Instant.parse("2026-09-28T07:10:21.315Z"));
        event.setLevel(Level.INFO);
        event.setLoggerName("org.springframework.boot.StartupInfoLogger");
        event.setMessage("애플리케이션을 시작했습니다.");
        event.setMDCPropertyMap(Map.of());

        var json = objectMapper.readTree(formatter.format(event));

        assertThat(json.path("event").isNull()).isTrue();
        assertThat(json.path("request_id").isNull()).isTrue();
        assertThat(json.path("release").asString()).isEqualTo("unknown");
    }
}
