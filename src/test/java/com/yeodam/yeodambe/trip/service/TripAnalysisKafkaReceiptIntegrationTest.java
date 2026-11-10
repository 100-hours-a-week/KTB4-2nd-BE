package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.integration.config.KafkaTopicProperties;
import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.event.TripCreateResultEvent;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.fixture.TripUploadTestErrorMessage;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "app.trip.edit-lock.enabled=true", "app.trip.edit-lock.wait-time=100ms",
        "app.trip.result-listener-enabled=true", "app.trip.result-worker-enabled=false",
        "app.trip.status-polling-enabled=false", "spring.kafka.admin.auto-create=true"
})
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TripAnalysisKafkaReceiptIntegrationTest.Topics.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TripAnalysisKafkaReceiptIntegrationTest {
    private static final String RUN = UUID.randomUUID().toString();
    private static final String RESULT_TOPIC = "test-trip-result-" + RUN;
    private static final String DLT_TOPIC = RESULT_TOPIC + "-dlt";
    private static final String GROUP = RESULT_TOPIC + "-consumer";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JdbcConnectionDetails database;
    @Autowired
    private KafkaConnectionDetails kafkaConnection;
    @Autowired
    private KafkaTemplate<String, String> kafka;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private TripAnalysisExecutionService scheduling;
    @Autowired
    private KafkaListenerEndpointRegistry listeners;
    @Autowired
    private TripEditLockProperties locks;
    @Autowired
    @Qualifier("tripEditRedissonClient")
    private RedissonClient redis;
    @MockitoSpyBean
    private TripAnalysisResultApplicationService receipt;

    private Long tripId;
    private String executionId;
    private String raw;

    @DynamicPropertySource
    static void configureTopics(DynamicPropertyRegistry registry) {
        registry.add("app.kafka.topics.trip-update-result", () -> RESULT_TOPIC);
        registry.add("app.kafka.dead-letter-topics.trip-update-result", () -> DLT_TOPIC);
        registry.add("KAFKA_TRIP_UPDATE_RESULT_CONSUMER_GROUP", () -> GROUP);
        registry.add("app.trip.edit-lock.namespace", () -> "test:result:" + RUN);
    }

    @BeforeEach
    void prepareAnalysis() {
        TripAnalysisTestFixture fixture = new TripAnalysisTestFixture(jdbc);
        Long ownerId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
        Long photoId = fixture.prepareAddition(tripId, ownerId).photoId();
        executionId = scheduling.startNext(tripId).orElseThrow();
        raw = json.writeValueAsString(new TripCreateResultEvent("process_result", tripId, executionId,
                TripCreateResultEvent.Status.COMPLETED, json.valueToTree(Map.of(
                        "places", List.of(), "failed", List.of(), "unclassified",
                        List.of(Map.of("trip_attachment_id", photoId, "region_origin", "UNKNOWN", "issue", "UNCLEAR_LOCATION")))), null));
        await().atMost(Duration.ofSeconds(20)).until(() -> listeners.getListenerContainers().stream()
                .filter(container -> container.getContainerProperties().getTopics() != null
                        && Arrays.asList(container.getContainerProperties().getTopics()).contains(RESULT_TOPIC))
                .anyMatch(container -> container.getAssignedPartitions() != null && !container.getAssignedPartitions().isEmpty()));
    }

    @ParameterizedTest
    @EnumSource(Fault.class)
    void 인프라_장애가_계속되면_원문_저장_전_Kafka_소비를_성공_커밋하지_않는다(Fault fault) throws Exception {
        TopicPartition resultPartition = new TopicPartition(RESULT_TOPIC, 0);
        TopicPartition dltPartition = new TopicPartition(DLT_TOPIC, 0);
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                String.join(",", kafkaConnection.getBootstrapServers())))) {
            RecordMetadata sent;
            try (AutoCloseable failure = inject(fault)) {
                sent = kafka.send(RESULT_TOPIC, tripId.toString(), raw).get(10, TimeUnit.SECONDS).getRecordMetadata();
                await().atMost(Duration.ofSeconds(12)).untilAsserted(() ->
                        verify(receipt, atLeast(5)).receive(any(), eq(raw)));
                assertThat(jdbc.queryForObject("SELECT result_raw_payload FROM trip_analysis_executions WHERE execution_id = ?",
                        String.class, executionId)).isNull();
                OffsetAndMetadata committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS).get(resultPartition);
                assertThat(committed == null || committed.offset() <= sent.offset()).isTrue();
                Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> end = admin.listOffsets(
                        Map.of(dltPartition, OffsetSpec.latest())).all().get(5, TimeUnit.SECONDS);
                assertThat(end.get(dltPartition).offset()).isZero();
            }
            await().atMost(Duration.ofSeconds(15)).until(() ->
                    jdbc.queryForObject("SELECT result_raw_payload FROM trip_analysis_executions WHERE execution_id = ?",
                            String.class, executionId) != null);
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                OffsetAndMetadata committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS).get(resultPartition);
                return committed != null && committed.offset() > sent.offset();
            });
            assertThat(jdbc.queryForObject("SELECT result_raw_payload FROM trip_analysis_executions WHERE execution_id = ?",
                    String.class, executionId)).isEqualTo(raw);
        }
    }

    private AutoCloseable inject(Fault fault) throws Exception {
        if (fault == Fault.REDIS_LOCK) {
            RLock lock = redis.getLock(locks.namespace() + ":lock:trip:mutation:" + tripId);
            lock.lock();
            return lock::unlock;
        }
        String trigger = "reject_kafka_result_" + tripId;
        try (Connection connection = DriverManager.getConnection(database.getJdbcUrl(), "root", database.getPassword());
             Statement ddl = connection.createStatement()) {
            ddl.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON trip_analysis_executions FOR EACH ROW "
                    + "BEGIN IF NEW.trip_id = " + tripId + " AND NEW.result_raw_payload IS NOT NULL THEN "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '"
                    + TripUploadTestErrorMessage.ANALYSIS_OUTBOX_FAILURE.message() + "'; END IF; END");
        }
        return () -> jdbc.execute("DROP TRIGGER " + trigger);
    }

    private enum Fault {
        REDIS_LOCK,
        MYSQL
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {
        @Bean
        NewTopic resultTopic() {
            return new NewTopic(RESULT_TOPIC, 1, (short) 1);
        }

        @Bean
        NewTopic initialResultTopic(KafkaTopicProperties topics) {
            return new NewTopic(topics.topicFor(EventType.TRIP_CREATE_RESULT), 1, (short) 1);
        }

        @Bean
        NewTopic resultDltTopic() {
            return new NewTopic(DLT_TOPIC, 1, (short) 1);
        }
    }
}
