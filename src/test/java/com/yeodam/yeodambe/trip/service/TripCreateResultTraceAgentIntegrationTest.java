package com.yeodam.yeodambe.trip.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.yeodam.yeodambe.SharedTestKafka;
import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.logging.BackendJsonLogFormatter;
import com.yeodam.yeodambe.integration.service.WorkflowTraceService;
import com.yeodam.yeodambe.integration.service.response.WorkflowTraceCarrier;
import com.yeodam.yeodambe.trip.event.TripCreateResultEvent;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {
        "app.trip.result-listener-enabled=true",
        "app.trip.result-worker-enabled=false",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@EnabledIfSystemProperty(named = "workflow.trace.agent.enabled", matches = "true")
class TripCreateResultTraceAgentIntegrationTest {
    private static final String TOPIC = "trace-" + UUID.randomUUID();
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-";
    @Autowired
    private KafkaTemplate<String, String> kafka;
    @Autowired
    private WorkflowTraceService traces;
    @MockitoBean
    private TripAnalysisResultService results;
    @MockitoBean
    private TripMutationCoordinator mutations;

    @DynamicPropertySource
    static void configureKafka(DynamicPropertyRegistry properties) throws Exception {
        SharedTestKafka.registerBootstrapServers(properties);
        SharedTestKafka.createTopics(TOPIC, TOPIC + "-dlt");
        properties.add("app.kafka.topics.trip-create-result", () -> TOPIC);
        properties.add("app.kafka.dead-letter-topics.trip-create-result", () -> TOPIC + "-dlt");
        properties.add("app.trip.result-consumer-group", () -> TOPIC);
    }

    @Test
    void 실제_String_Listener의_부모와_샘플링을_보존하고_오류_Header도_처리한다() throws Exception {
        Map<String, SpanContext> received = new ConcurrentHashMap<>();
        Map<String, JsonNode> logs = new ConcurrentHashMap<>();
        CountDownLatch completed = new CountDownLatch(5);
        ObjectMapper json = new ObjectMapper();
        ThrowableProxyConverter converter = new ThrowableProxyConverter();
        converter.start();
        BackendJsonLogFormatter formatter = new BackendJsonLogFormatter(new MockEnvironment(), converter);
        doAnswer(call -> {
            Runnable operation = call.getArgument(1);
            operation.run();
            return null;
        }).when(mutations).run(anyLong(), any());
        doAnswer(call -> {
            TripCreateResultEvent event = call.getArgument(0);
            received.put(event.executionId(), Span.current().getSpanContext());
            LoggingEvent log = new LoggingEvent();
            log.setInstant(Instant.now());
            log.setLevel(Level.INFO);
            log.setLoggerName("synthetic-result");
            log.setMessage("synthetic result");
            log.setMDCPropertyMap(Map.of("job_id", event.executionId()));
            logs.put(event.executionId(), json.readTree(formatter.format(log)));
            completed.countDown();
            return null;
        }).when(results).receiveKafkaResult(any(), any());

        send("sampled", PARENT + "01", false);
        send("unsampled", PARENT + "00", false);
        send("missing", null, false);
        send("invalid", "invalid", false);
        send("duplicate", PARENT + "01", true);
        assertThat(completed.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get("sampled").getTraceId()).isEqualTo(TRACE_ID);
        assertThat(received.get("sampled").getSpanId()).isNotEqualTo("00f067aa0ba902b7");
        assertThat(received.get("sampled").isSampled()).isTrue();
        assertThat(received.get("unsampled").getTraceId()).isEqualTo(TRACE_ID);
        assertThat(received.get("unsampled").isSampled()).isFalse();
        assertThat(received.get("missing").getTraceId()).isNotEqualTo(TRACE_ID);
        assertThat(received.get("invalid").getTraceId()).isNotEqualTo(TRACE_ID);
        received.forEach((job, context) -> {
            if (context.isValid()) {
                assertThat(logs.get(job).path("trace_id").asString()).isEqualTo(context.getTraceId());
                assertThat(logs.get(job).path("span_id").asString()).isEqualTo(context.getSpanId());
            } else {
                assertThat(logs.get(job).path("trace_id").isNull()).isTrue();
                assertThat(logs.get(job).path("span_id").isNull()).isTrue();
            }
        });
        Span first = traces.startResultApplication(new WorkflowTraceCarrier(PARENT + "01", null));
        Span second = traces.startResultApplication(new WorkflowTraceCarrier(PARENT + "01", null));
        try {
            assertThat(first.getSpanContext().getTraceId()).isEqualTo(TRACE_ID);
            assertThat(second.getSpanContext().getTraceId()).isEqualTo(TRACE_ID);
            assertThat(first.getSpanContext().getSpanId()).isNotEqualTo(second.getSpanContext().getSpanId());
        } finally {
            traces.end(first);
            traces.end(second);
        }
    }

    private void send(String job, String traceparent, boolean duplicate) throws Exception {
        String payload = "{\"type\":\"process_result\",\"trip_id\":7,\"execution_id\":\"" + job
                + "\",\"status\":\"COMPLETED\",\"result\":{}}";
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, 0, "7", payload);
        if (traceparent != null) {
            record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
        }
        if (duplicate) {
            record.headers().add("traceparent", "invalid".getBytes(StandardCharsets.UTF_8));
        }
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }
}
