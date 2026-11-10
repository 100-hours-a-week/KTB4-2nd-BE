package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import static org.mockito.ArgumentMatchers.argThat;
import org.springframework.dao.DataAccessResourceFailureException;
import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.integration.config.KafkaTopicProperties;
import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;
import com.yeodam.yeodambe.integration.service.OutboxReceiptService;
import com.yeodam.yeodambe.trip.fixture.TripUploadTestErrorMessage;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadOutboxRepository;
import com.yeodam.yeodambe.integration.CdcTestEnvironment;
import com.yeodam.yeodambe.trip.client.AttachmentConversionReceiptListener;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.AttachmentConversionOutboxRepository;
import com.yeodam.yeodambe.trip.repository.AttachmentConversionTaskRepository;
import com.yeodam.yeodambe.trip.service.request.AttachmentConversionMessage;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "app.attachment-conversion.mode=HOLD",
        "app.attachment-conversion.retry.technical-backoff=100ms",
        "spring.kafka.listener.auto-startup=false",
        "app.outbox.receipt-listener-enabled=false"
})
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AttachmentConversionCdcIntegrationTest.EnvironmentConfiguration.class})
@EnabledIfEnvironmentVariable(named = "CDC_INTEGRATION_ENABLED", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AttachmentConversionCdcIntegrationTest {
    @MockitoSpyBean private AdditionalAttachmentUploadOutboxRepository analysisOutboxes;
    private static final String GROUP = "yeodam-image-conversion-receipts";
    private static final List<String> TOPICS = List.of("yeodam-image-initial-light", "yeodam-image-initial-heic",
            "yeodam-image-additional-light", "yeodam-image-additional-heic");
    private final HttpClient http = HttpClient.newHttpClient();
    @Autowired private CdcTestEnvironment environment;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private AttachmentConversionOutboxRepository outboxes;
    @Autowired private AttachmentConversionTaskRepository tasks;
    @MockitoSpyBean private JdbcTemplate jdbc;
    @Autowired private KafkaListenerEndpointRegistry registry;
    @Autowired private KafkaTemplate<String, String> kafka;
    @Autowired private ObjectMapper json;
    @Autowired private ApplicationContext context;

    @Test
    void 한_커밋의_분석_메시지는_실제_CDC에서_준비_메시지_뒤에_process로_전달된다() throws Exception {
        String topic = analysisTopic();
        TripAnalysisTestFixture fixture =
                new TripAnalysisTestFixture(jdbc);
        Long ownerId = fixture.createOwner();
        Long tripId = fixture.createTrip(ownerId);
        fixture.prepareAddition(tripId, ownerId);
        fixture.prepareAddition(tripId, ownerId);
        String executionId = context.getBean(TripAnalysisExecutionService.class).startNext(tripId).orElseThrow();
        List<ConsumerRecord<String, String>> records = receiveAnalysis(topic, tripId);
        assertThat(records.stream().map(record -> json.readTree(record.value()).path("type").asString()))
                .containsExactly("photos_ready", "photos_ready", "process");
        for (ConsumerRecord<String, String> record : records) {
            assertThat(record.key()).isEqualTo(tripId.toString());
            assertThat(header(record, "outbox-source")).isEqualTo("additional_attachment_upload_outbox");
            assertThat(json.readTree(record.value()).path("execution_id").asString()).isEqualTo(executionId);
            context.getBean(OutboxReceiptService.class).observe(
                    header(record, "outbox-source"), header(record, "id"),
                    new OutboxReceipt(
                            record.topic(), record.key(), record.value(), Instant.ofEpochMilli(record.timestamp())));
        }
        assertThat(jdbc.queryForObject("""
                SELECT process_published_at IS NOT NULL FROM trip_analysis_executions WHERE execution_id = ?
                """, Boolean.class, executionId)).isTrue();
    }

    @Test
    void 분석_입력_저장이_롤백되면_준비_메시지도_CDC로_전달되지_않는다() throws Exception {
        String topic = analysisTopic();
        TripAnalysisTestFixture fixture =
                new TripAnalysisTestFixture(jdbc);
        Long ownerId = fixture.createOwner();
        Long failedTripId = fixture.createTrip(ownerId);
        fixture.prepareAddition(failedTripId, ownerId);
        doThrow(new DataAccessResourceFailureException(
                TripUploadTestErrorMessage.ANALYSIS_OUTBOX_FAILURE.message()))
                .when(analysisOutboxes).saveAndFlush(argThat(outbox ->
                        failedTripId.equals(outbox.getTripId()) && "process".equals(outbox.getMessageType())));
        assertThatThrownBy(() -> context.getBean(TripAnalysisExecutionService.class).startNext(failedTripId))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_analysis_executions WHERE trip_id = ?",
                Integer.class, failedTripId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM additional_attachment_upload_outbox WHERE trip_id = ?",
                Integer.class, failedTripId)).isZero();
        Long controlOwner = fixture.createOwner();
        Long controlTripId = fixture.createTrip(controlOwner);
        fixture.prepareAddition(controlTripId, controlOwner);
        context.getBean(TripAnalysisExecutionService.class).startNext(controlTripId).orElseThrow();
        List<ConsumerRecord<String, String>> observed = new ArrayList<>();
        try (KafkaConsumer<String, String> reader = consumer()) {
            reader.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                reader.poll(Duration.ofMillis(100)).forEach(observed::add);
                return observed.stream().anyMatch(record -> controlTripId.toString().equals(record.key())
                        && "process".equals(json.readTree(record.value()).path("type").asString()));
            });
        }
        assertThat(observed).noneMatch(record -> failedTripId.toString().equals(record.key()));
    }

    private String analysisTopic() throws Exception {
        String topic = context.getBean(KafkaTopicProperties.class)
                .topicFor(EventType.TRIP_UPDATE_REQUEST);
        try (AdminClient administration = admin()) {
            if (!administration.listTopics().names().get(10, TimeUnit.SECONDS).contains(topic)) {
                administration.createTopics(List.of(new NewTopic(topic, 1, (short) 1)
                        .configs(Map.of("message.timestamp.type", "LogAppendTime"))))
                        .all().get(20, TimeUnit.SECONDS);
            }
        }
        return topic;
    }

    private List<ConsumerRecord<String, String>> receiveAnalysis(String topic, Long tripId) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> reader = consumer()) {
            reader.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (ConsumerRecord<String, String> record : reader.poll(Duration.ofMillis(100))) {
                    if (tripId.toString().equals(record.key())) {
                        records.add(record);
                    }
                }
                return records.stream().anyMatch(record -> "process".equals(json.readTree(record.value()).path("type").asString()));
            });
        }
        return records;
    }

    @BeforeAll
    void startConnectorAndReceipt() throws Exception {
        try (var admin = admin()) {
            admin.createTopics(TOPICS.stream().map(topic -> new NewTopic(topic, 1, (short) 1)
                    .configs(Map.of("message.timestamp.type", "LogAppendTime"))).toList()).all().get(20, TimeUnit.SECONDS);
        }
        var listener = registry.getListenerContainer("attachment-conversion-receipts");
        if (listener != null) {
            listener.start();
        }
        environment.startConnect();
        Map<String, String> config;
        try (var input = getClass().getResourceAsStream("/cdc/connector.json")) {
            config = json.readValue(input, new TypeReference<>() {});
        }
        String endpoint = "http://" + environment.connect().getHost() + ":" + environment.connect().getMappedPort(8083);
        request(endpoint + "/connectors", "POST", json.writeValueAsString(Map.of("name", "outbox", "config", config)));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var status = json.readTree(request(endpoint + "/connectors/outbox/status", "GET", null));
            assertThat(status.path("tasks").isEmpty()).isFalse();
            assertThat(status.path("tasks").get(0).path("state").asString()).isEqualTo("RUNNING");
        });
    }

    @Test
    void 토픽_ID를_알_수_없는_수신_확인도_위치를_보존한다() throws Exception {
        assertThat(context.getBeansOfType(AttachmentConversionWorker.class)).isEmpty();
        for (var kind : AttachmentConversionTask.Kind.values()) {
            for (var lane : AttachmentConversionTask.Lane.values()) {
                var fixture = fixture(kind, lane, false);
                var record = receive(fixture.outboxId());
                assertWire(record, fixture);
                awaitPublished(fixture.outboxId());
                var stored = outboxes.findById(fixture.outboxId()).orElseThrow();
                assertThat(stored.getRecordOffset()).isEqualTo(record.offset());
                assertThat(stored.getRecordPartition()).isEqualTo(record.partition());
                assertThat(stored.getBrokerTimestamp()).isEqualTo(Instant.ofEpochMilli(record.timestamp()));
                assertThat(stored.getTopicId()).isNull();
                assertThat(stored.getPublicationVersion()).isEqualTo(1);
                context.getBean(AttachmentConversionReceiptListener.class).consume(record);
                var repeated = outboxes.findById(fixture.outboxId()).orElseThrow();
                assertThat(repeated.getPublicationVersion()).isEqualTo(stored.getPublicationVersion());
                assertThat(repeated.getPublishedAt()).isEqualTo(stored.getPublishedAt());
                assertThat(tasks.findById(fixture.taskId()).orElseThrow().getStatus()).isEqualTo(AttachmentConversionTask.Status.WAITING);
                String executionTable = kind == AttachmentConversionTask.Kind.INITIAL
                        ? "trip_creation_executions" : "additional_attachment_executions";
                assertThat(jdbc.queryForObject("SELECT process_published_at FROM " + executionTable + " WHERE trip_id = ?",
                        LocalDateTime.class, fixture.tripId())).isNull();
            }
        }
    }

    @Test
    void 롤백하면_CDC로_아무것도_발행하지_않는다() throws Exception {
        var rolledBack = fixture(AttachmentConversionTask.Kind.INITIAL, AttachmentConversionTask.Lane.LIGHT, true);
        var committed = fixture(AttachmentConversionTask.Kind.INITIAL, AttachmentConversionTask.Lane.LIGHT, false);
        var records = new ArrayList<ConsumerRecord<String, String>>();
        try (var consumer = consumer()) {
            consumer.subscribe(TOPICS);
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(100)).forEach(records::add);
                return records.stream().anyMatch(record -> committed.outboxId().equals(header(record, "id")));
            });
            for (int attempt = 0; attempt < 10; attempt++) {
                consumer.poll(Duration.ofMillis(100)).forEach(records::add);
            }
        }
        assertThat(records).noneMatch(record -> rolledBack.outboxId().equals(header(record, "id")));
        assertThat(outboxes.findById(rolledBack.outboxId())).isEmpty();
        awaitPublished(committed.outboxId());
    }

    @Test
    void 수신_확인이_실패해도_원래_위치를_잃지_않는다() throws Exception {
        var unavailable = new AtomicBoolean(true);
        var failures = new AtomicInteger();
        doAnswer(invocation -> {
            if (unavailable.get()) {
                failures.incrementAndGet();
                throw new TransientDataAccessResourceException("injected DB outage");
            }
            return invocation.callRealMethod();
        }).when(jdbc).queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
        try {
            var fixture = fixture(AttachmentConversionTask.Kind.ADDITIONAL, AttachmentConversionTask.Lane.HEIC, false);
            var record = receive(fixture.outboxId());
            await().atMost(Duration.ofSeconds(20)).until(() -> failures.get() >= 3);
            assertThat(outboxes.findById(fixture.outboxId()).orElseThrow().getStatus()).isEqualTo(AttachmentConversionOutbox.Status.PENDING);
            try (var admin = admin()) {
                var offset = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)
                        .get(new TopicPartition(record.topic(), record.partition()));
                assertThat(offset == null || offset.offset() <= record.offset()).isTrue();
            }
            unavailable.set(false);
            awaitPublished(fixture.outboxId());
            assertThat(outboxes.findById(fixture.outboxId()).orElseThrow().getRecordOffset()).isEqualTo(record.offset());
            try (var admin = admin()) {
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    var offset = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)
                            .get(new TopicPartition(record.topic(), record.partition()));
                    assertThat(offset).isNotNull();
                    assertThat(offset.offset()).isEqualTo(record.offset() + 1);
                });
            }
        } finally {
            unavailable.set(false);
        }
    }

    @Test
    void 잘못된_수신_확인은_제어_그룹을_막지_않는다() throws Exception {
        // Direct send exercises control-group poison handling; the valid observation below still goes through Connect.
        var bad = new ProducerRecord<String, String>(TOPICS.getFirst(), "7", "secret malformed payload");
        bad.headers().add("outbox-source", "attachment_conversion_outbox".getBytes(StandardCharsets.UTF_8));
        kafka.send(bad).get(10, TimeUnit.SECONDS);
        var unknown = new ProducerRecord<String, String>(TOPICS.getFirst(), "7", "{}");
        unknown.headers().add("outbox-source", "attachment_conversion_outbox".getBytes(StandardCharsets.UTF_8));
        unknown.headers().add("id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        kafka.send(unknown).get(10, TimeUnit.SECONDS);
        var fixture = fixture(AttachmentConversionTask.Kind.INITIAL, AttachmentConversionTask.Lane.LIGHT, false);
        receive(fixture.outboxId());
        awaitPublished(fixture.outboxId());
        var listener = registry.getListenerContainer("attachment-conversion-receipts");
        assertThat(listener.getGroupId()).isEqualTo(GROUP);
        assertThat(listener.isRunning()).isTrue();
    }

    private Fixture fixture(AttachmentConversionTask.Kind kind, AttachmentConversionTask.Lane lane, boolean rollback) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            var user = new User(UUID.randomUUID() + "@yeodam.test", "receipt");
            entityManager.persist(user);
            var trip = new Trip(user.getUserId(), "receipt", LocalDate.now(), LocalDate.now());
            entityManager.persist(trip);
            String executionId = UUID.randomUUID().toString();
            Long batchId;
            if (kind == AttachmentConversionTask.Kind.INITIAL) {
                entityManager.persist(new TripCreationExecution(executionId, trip.getId(), user.getUserId(), 1));
                var batch = new InitialAttachmentUploadBatch(UUID.randomUUID().toString(), executionId,
                        trip.getId(), user.getUserId(), 1, 1, true);
                entityManager.persist(batch);
                batchId = batch.getId();
            } else {
                entityManager.persist(new AdditionalAttachmentExecution(executionId, trip.getId(), user.getUserId(), 1, LocalDateTime.now()));
                var batch = new AdditionalAttachmentUploadBatch(UUID.randomUUID().toString(), executionId,
                        trip.getId(), user.getUserId(), 1, 1, true);
                entityManager.persist(batch);
                batchId = batch.getId();
            }
            var task = new AttachmentConversionTask(UUID.randomUUID().toString(), kind, batchId, trip.getId(), lane, 1, 100);
            entityManager.persist(task);
            String topic = "yeodam-image-" + kind.name().toLowerCase(Locale.ROOT) + "-" + lane.name().toLowerCase(Locale.ROOT);
            String payload = json.writeValueAsString(new AttachmentConversionMessage(1, task.getId()));
            var outbox = new AttachmentConversionOutbox(UUID.randomUUID().toString(), task.getId(), 1, trip.getId(), topic, payload, "trace-conversion");
            entityManager.persist(outbox);
            entityManager.flush();
            if (rollback) {
                status.setRollbackOnly();
            }
            return new Fixture(outbox.getId(), task.getId(), trip.getId(), topic, payload);
        });
    }

    private void awaitPublished(String id) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(outboxes.findById(id).orElseThrow().getStatus()).isEqualTo(AttachmentConversionOutbox.Status.PUBLISHED));
    }

    private ConsumerRecord<String, String> receive(String id) {
        var found = new ArrayList<ConsumerRecord<String, String>>();
        try (var consumer = consumer()) {
            consumer.subscribe(TOPICS);
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(100)).forEach(record -> {
                    if (id.equals(header(record, "id"))) {
                        found.add(record);
                    }
                });
                return !found.isEmpty();
            });
        }
        return found.getFirst();
    }

    private KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of("bootstrap.servers", environment.kafka().getBootstrapServers(),
                "group.id", UUID.randomUUID().toString(), "auto.offset.reset", "earliest", "enable.auto.commit", false,
                "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"));
    }

    private AdminClient admin() {
        return AdminClient.create(Map.of("bootstrap.servers", environment.kafka().getBootstrapServers()));
    }

    private void assertWire(ConsumerRecord<String, String> record, Fixture fixture) {
        assertThat(record.topic()).isEqualTo(fixture.topic());
        assertThat(record.key()).isEqualTo(fixture.tripId().toString());
        assertThat(record.value()).isEqualTo(fixture.payload());
        assertThat(header(record, "id")).isEqualTo(fixture.outboxId());
        assertThat(header(record, "outbox-source")).isEqualTo("attachment_conversion_outbox");
        assertThat(header(record, "X-Request-ID")).isEqualTo("trace-conversion");
        assertThat(record.timestampType()).isEqualTo(org.apache.kafka.common.record.TimestampType.LOG_APPEND_TIME);
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private String request(String url, String method, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage(response.body()).isBetween(200, 299);
        return response.body();
    }

    private record Fixture(
            String outboxId,
            String taskId,
            Long tripId,
            String topic,
            String payload
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EnvironmentConfiguration {
        @Bean(destroyMethod = "close")
        CdcTestEnvironment cdcTestEnvironment() {
            return new CdcTestEnvironment();
        }

        @Bean
        @Primary
        JdbcConnectionDetails cdcJdbcConnectionDetails(CdcTestEnvironment environment) {
            return new JdbcConnectionDetails() {
                @Override
                public String getJdbcUrl() {
                    return environment.mysql().getJdbcUrl();
                }

                @Override
                public String getUsername() {
                    return "root";
                }

                @Override
                public String getPassword() {
                    return environment.mysql().getPassword();
                }
            };
        }

        @Bean
        @Primary
        KafkaConnectionDetails cdcKafkaConnectionDetails(CdcTestEnvironment environment) {
            return () -> List.of(environment.kafka().getBootstrapServers());
        }
    }
}
