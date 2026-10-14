package com.yeodam.yeodambe.integration.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yeodam.yeodambe.common.logging.BackendJsonLogFormatter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaDltLogServiceTest {

    private final KafkaDltLogService service = new KafkaDltLogService();
    private final Logger logger = (Logger) LoggerFactory.getLogger(KafkaDltLogService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private BackendJsonLogFormatter formatter;

    @BeforeEach
    void captureLogs() {
        var converter = new ThrowableProxyConverter();
        converter.start();
        formatter = new BackendJsonLogFormatter(new MockEnvironment(), converter);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void 처리_예외와_마지막_시도_횟수만_기록한다() {
        var record = new ConsumerRecord<>("source-topic", 1, 12, "42", "{secret-payload");
        record.headers().add(KafkaHeaders.DELIVERY_ATTEMPT, ByteBuffer.allocate(4).putInt(2).array());
        record.headers().add(KafkaHeaders.DELIVERY_ATTEMPT, ByteBuffer.allocate(4).putInt(4).array());
        var failure = new ListenerExecutionFailedException("wrapper-secret",
                new IllegalArgumentException("credential-secret"));

        service.logPublished(record, failure, "result-dlt");

        assertThat(appender.list).hasSize(1);
        String output = formatter.format(appender.list.getFirst());
        var json = mapper.readTree(output);
        assertThat(json.path("event").asString()).isEqualTo("kafka_dlt_published");
        assertThat(json.path("service").asString()).isEqualTo("be");
        assertThat(json.path("dlt_topic").asString()).isEqualTo("result-dlt");
        assertThat(json.path("source_topic").asString()).isEqualTo("source-topic");
        assertThat(json.path("trip_id").asString()).isEqualTo("42");
        assertThat(json.path("failure").asString()).isEqualTo("IllegalArgumentException");
        assertThat(json.path("attempts").asInt()).isEqualTo(4);
        assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
        assertThat(output).doesNotContain("secret-payload", "wrapper-secret", "credential-secret");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-trip", "0", "-1", "999999999999999999999999"})
    void 식별할_수_없는_key는_null로_기록한다(String key) {
        var record = new ConsumerRecord<>("source", 0, 0, key, "malformed-json");

        service.logPublished(record, new IllegalArgumentException(), "dlt");

        var json = mapper.readTree(formatter.format(appender.list.getFirst()));
        assertThat(json.path("trip_id").isNull()).isTrue();
        assertThat(json.path("attempts").asInt()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 3})
    void 양의_시도_횟수만_사용한다(int attempts) {
        var record = new ConsumerRecord<>("source", 0, 0, "7", "broken");
        record.headers().add(KafkaHeaders.DELIVERY_ATTEMPT,
                ByteBuffer.allocate(Integer.BYTES).putInt(attempts).array());

        service.logPublished(record, new IllegalArgumentException(), "dlt");

        var json = mapper.readTree(formatter.format(appender.list.getFirst()));
        assertThat(json.path("attempts").asInt()).isEqualTo(attempts > 0 ? attempts : 1);
    }

    @Test
    void 잘못된_헤더_길이는_첫_시도로_기록한다() {
        var record = new ConsumerRecord<>("source", 0, 0, "7", "broken");
        record.headers().add(KafkaHeaders.DELIVERY_ATTEMPT, new byte[] {1});

        service.logPublished(record, new IllegalArgumentException(), "dlt");

        var json = mapper.readTree(formatter.format(appender.list.getFirst()));
        assertThat(json.path("attempts").asInt()).isEqualTo(1);
    }
}
