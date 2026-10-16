package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;
import java.time.Instant;
import java.util.Comparator;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.SharedTestKafka;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.request.*;
import com.yeodam.yeodambe.trip.service.response.*;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "app.trip.result-listener-enabled=true"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripCreationKafkaFlowIntegrationTest {

    private static final String BOOTSTRAP_SERVERS = SharedTestKafka.bootstrapServers();

    private static final String PREFIX = "trip-flow-" + UUID.randomUUID();
    private static final String REQUEST_TOPIC = PREFIX + ".request";
    private static final String RESULT_TOPIC = PREFIX + ".result";
    private static final String DLT_TOPIC = RESULT_TOPIC + "-dlt";

    @DynamicPropertySource
    static void configureKafka(DynamicPropertyRegistry registry) throws Exception {
        SharedTestKafka.registerBootstrapServers(registry);
        String updateResultTopic = PREFIX + ".update-result";
        SharedTestKafka.createTopics(REQUEST_TOPIC, RESULT_TOPIC, DLT_TOPIC,
                updateResultTopic, updateResultTopic + "-dlt");
        registry.add("app.kafka.topics.trip-create-request", () -> REQUEST_TOPIC);
        registry.add("app.kafka.topics.trip-create-result", () -> RESULT_TOPIC);
        registry.add("app.kafka.dead-letter-topics.trip-create-result", () -> DLT_TOPIC);
        registry.add("app.kafka.topics.trip-update-result", () -> updateResultTopic);
        registry.add("app.kafka.dead-letter-topics.trip-update-result", () -> updateResultTopic + "-dlt");
        registry.add("app.trip.result-consumer-group", () -> PREFIX + ".create-consumer");
        registry.add("KAFKA_TRIP_UPDATE_RESULT_CONSUMER_GROUP", () -> PREFIX + ".update-consumer");
    }

    @Autowired
    private InitialAttachmentUploadTransactionService transactions;
    @Autowired
    private TripAnalysisResultService results;
    @Autowired
    private TripAnalysisResultWorker worker;
    @Autowired
    private TripCreationExecutionRepository executions;
    @Autowired
    private InitialAttachmentUploadBatchRepository batches;
    @Autowired
    private TripRepository trips;
    @Autowired
    private TripRegionRepository regions;
    @Autowired
    private UserRepository users;
    @Autowired
    private UserStatsRepository stats;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private TripPlaceNameService names;
    @Autowired
    private TripCreationOutboxRepository outboxes;
    @Autowired
    private KafkaTemplate<String, String> kafka;
    private User owner;
    private Trip trip;

    // BE does not publish requests in production; this fixture simulates the CDC boundary.
    private void publishOutboxFixture() throws Exception {
        for (int pass = 0; pass < 2; pass++) {
            var pending = outboxes.findAll().stream()
                    .filter(value -> value.getTripId().equals(trip.getId()) && value.getPublishedAt() == null)
                    .sorted(Comparator.comparing(TripCreationOutbox::getCreatedAt)
                            .thenComparing(TripCreationOutbox::getBatchNo))
                    .toList();
            for (var event : pending) {
                kafka.send(event.getTopic(), event.getTripId().toString(), event.getPayload()).get(10, TimeUnit.SECONDS);
                transactions.recordPublished(event.getId(), new OutboxReceipt(
                        event.getTopic(), event.getTripId().toString(), event.getPayload(), Instant.now()));
            }
        }
    }

    @BeforeEach
    void prepareUser() {
        owner = users.save(new User(UUID.randomUUID() + "@yeodam.test", "업로드회원"));
        stats.save(new UserStats(owner));
        trip = trips.save(new Trip(owner.getUserId(), "직접 업로드", LocalDate.now(), LocalDate.now()));
        regions.save(new TripRegion(trip, "11000", "서울특별시", BigDecimal.ONE, BigDecimal.TEN));
        when(names.resolve(any(), any(), any(), any())).thenReturn(Map.of());
    }

    @Test
    void 실제_Kafka로_23장_10_10_3을_발행하고_종료결과를_원문으로_인계한다() throws Exception {
        var first = prepare(1, 10, false);
        accept(first);
        finishConversion(transactions.claimBatch().orElseThrow());
        var second = prepare(2, 10, false);
        accept(second);
        var last = prepare(3, 3, true);
        accept(last);
        var secondClaim = transactions.claimBatch().orElseThrow();
        var lastClaim = transactions.claimBatch().orElseThrow();
        assertThat(lastClaim.batchNo()).isEqualTo(3);
        finishConversion(lastClaim);
        publishOutboxFixture();
        assertThat(execution().getProcessPayload()).isNull();
        finishConversion(secondClaim);
        publishOutboxFixture();
        assertThat(execution().getProcessDeliveryStatus()).isEqualTo(InitialAttachmentDeliveryStatus.SENT);
        var process = json.readTree(execution().getProcessPayload());
        assertThat(process.path("attachments").size()).isEqualTo(23);
        assertThat(process.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                "type", "trip_id", "execution_id", "trigger", "trip_name", "period", "regions", "attachments");
        assertThat(execution().getProcessPayload().getBytes(StandardCharsets.UTF_8).length).isLessThan(1024 * 1024);
        var consumerProperties = new Properties();
        consumerProperties.put("bootstrap.servers", BOOTSTRAP_SERVERS);
        consumerProperties.put("group.id", "trip-wire-test-" + UUID.randomUUID());
        consumerProperties.put("enable.auto.commit", "false");
        consumerProperties.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumerProperties.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (var consumer = new KafkaConsumer<String, String>(consumerProperties)) {
            var partitions = consumer.partitionsFor(REQUEST_TOPIC).stream()
                    .map(partition -> new TopicPartition(partition.topic(), partition.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            var received = new ArrayList<JsonNode>();
            for (int attempt = 0; attempt < 20 && received.size() < 4; attempt++) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    var event = json.readTree(record.value());
                    if (trip.getId().toString().equals(record.key())
                            && execution().getExecutionId().equals(event.path("execution_id").asString())) {
                        received.add(event);
                    }
                }
            }
            assertThat(received).extracting(event -> event.path("type").asString())
                    .containsExactly("photos_ready", "photos_ready", "photos_ready", "process");
            assertThat(received.subList(0, 3)).extracting(event -> event.path("attachments").size())
                    .containsExactly(10, 3, 10);
        }
        String raw = resultPayload(process);
        kafka.send(RESULT_TOPIC, trip.getId().toString(), raw).get(10, TimeUnit.SECONDS);
        for (int attempt = 0; attempt < 100 && execution().getResultRawPayload() == null; attempt++) {
            Thread.sleep(100);
        }
        assertThat(execution().getResultRawPayload()).isEqualTo(raw);
        assertThat(execution().getStatus()).isEqualTo(TripCreationExecution.Status.RESULT_APPLYING);
        worker.applyPendingResults();
        assertThat(execution().getStatus()).isEqualTo(TripCreationExecution.Status.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT storage_used_bytes FROM user_stats WHERE user_id = ?", Long.class, owner.getUserId()))
                .isEqualTo(23 * 12L);
    }

    @Test
    void 해석할_수_없는_결과는_실제_DLT로_격리한다() throws Exception {
        String key = "invalid-result-" + UUID.randomUUID();
        String raw = "{invalid-json";
        var properties = new Properties();
        properties.put("bootstrap.servers", BOOTSTRAP_SERVERS);
        properties.put("group.id", "trip-dlt-test-" + UUID.randomUUID());
        properties.put("enable.auto.commit", "false");
        properties.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            var partitions = consumer.partitionsFor(DLT_TOPIC).stream()
                    .map(partition -> new TopicPartition(partition.topic(), partition.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToEnd(partitions);
            partitions.forEach(consumer::position);
            kafka.send(RESULT_TOPIC, key, raw).get(10, TimeUnit.SECONDS);
            String received = null;
            for (int attempt = 0; attempt < 20 && received == null; attempt++) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        received = record.value();
                    }
                }
            }
            assertThat(received).isEqualTo(raw);
            assertThat(executions.findByTripId(trip.getId())).isEmpty();
        }
    }

    private InitialAttachmentUploadBatchSnapshot prepare(
            int number,
            int size,
            boolean last
    ) {
        return transactions.prepareBatch(trip.getId(), owner.getUserId(), new InitialAttachmentUploadUrlRequest(
                number, 23, last, IntStream.range(0, size)
                        .mapToObj(i -> new InitialAttachmentUploadUrlRequest.Attachment("photo.jpg", "image/jpeg", 12L)).toList()));
    }

    private void accept(InitialAttachmentUploadBatchSnapshot batch) {
        transactions.startProcessing(trip.getId(), owner.getUserId(), batch.uploadId());
    }

    private void finishConversion(InitialAttachmentUploadBatchSnapshot claim) {
        for (var photo : claim.photos()) {
            var keys = transactions.startPhotoAttempt(claim, photo.itemId());
            transactions.savePhotoSuccess(claim, photo.itemId(), new DerivedPhotoKeys(photo.objectKey(), keys.get(0), keys.get(1),
                    null, null, null, null, null, 12L, 8L, 4L, null));
        }
        transactions.completeBatch(claim);
    }

    private TripCreationExecution execution() {
        return executions.findByTripId(trip.getId()).orElseThrow();
    }

    private String resultPayload(JsonNode process) {
        var root = json.createObjectNode();
        root.put("type", "process_result").put("trip_id", trip.getId()).put("execution_id", execution().getExecutionId()).put("status", "COMPLETED");
        root.putNull("error");
        var result = root.putObject("result");
        result.putArray("places");
        result.putArray("failed");
        var photos = result.putArray("unclassified");
        for (var photo : process.path("attachments")) {
            photos.addObject().put("trip_attachment_id", photo.path("trip_attachment_id").asLong())
                    .put("issue", "BLURRY").put("region_origin", "UNKNOWN").put("evaluation", 31);
        }
        return json.writeValueAsString(root);
    }
}
