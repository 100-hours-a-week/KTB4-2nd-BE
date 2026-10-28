package com.yeodam.websocket.common.websocket.logging;

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

class WebSocketJsonLogFormatterTest {

    @Test
    void writesOneJsonLineWithSearchableFieldsAndEscapedStackTrace() {
        var converter = new ThrowableProxyConverter();
        converter.start();
        var formatter = new WebSocketJsonLogFormatter(new MockEnvironment().withProperty("RELEASE", "test-sha"), converter);
        var event = event();
        event.setMDCPropertyMap(Map.of());
        event.setKeyValuePairs(List.of(new KeyValuePair("event", "websocket_event_dispatch"),
                new KeyValuePair("result", "failure"), new KeyValuePair("failure", "DELIVERY_FAILED"),
                new KeyValuePair("event_id", "event-1"), new KeyValuePair("connection_id", "connection-1")));
        event.setThrowableProxy(new ThrowableProxy(new IllegalStateException("test\nerror")));

        String output = formatter.format(event);
        var json = new ObjectMapper().readTree(output);

        assertThat(output.lines().count()).isEqualTo(1);
        assertThat(json.path("service").asString()).isEqualTo("websocket");
        assertThat(json.path("release").asString()).isEqualTo("test-sha");
        assertThat(json.path("event").asString()).isEqualTo("websocket_event_dispatch");
        assertThat(json.path("failure").asString()).isEqualTo("DELIVERY_FAILED");
        assertThat(json.path("event_id").asString()).isEqualTo("event-1");
        assertThat(json.path("connection_id").asString()).isEqualTo("connection-1");
        assertThat(json.path("stack_trace").asString()).contains("test\nerror");
    }

    @Test
    void defaultsReleaseAndExcludesUnapprovedContextFields() {
        var converter = new ThrowableProxyConverter();
        converter.start();
        var event = event();
        event.setMDCPropertyMap(Map.of("accessToken", "test-secret", "payload", "private-body"));
        String output = new WebSocketJsonLogFormatter(new MockEnvironment(), converter).format(event);
        var json = new ObjectMapper().readTree(output);

        assertThat(json.path("release").asString()).isEqualTo("unknown");
        assertThat(json.path("event").isNull()).isTrue();
        assertThat(output).doesNotContain("test-secret", "private-body");
    }

    private LoggingEvent event() {
        var event = new LoggingEvent();
        event.setInstant(Instant.parse("2026-10-28T00:00:00Z"));
        event.setLevel(Level.WARN);
        event.setLoggerName("test.websocket");
        event.setMessage("WebSocket test log.");
        return event;
    }
}
