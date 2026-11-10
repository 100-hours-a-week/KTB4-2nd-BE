package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.YeodamBeApplication;
import com.yeodam.yeodambe.common.exception.AttachmentUploadLimitExceededException;
import com.yeodam.yeodambe.trip.event.TripCreateResultEvent;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture.Addition;
import com.yeodam.yeodambe.trip.fixture.TripUploadTestErrorMessage;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.service.request.TripUpdateRequest;
import com.yeodam.yeodambe.trip.service.response.TripAnalysisResultClaim;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.trip.edit-lock.enabled=true", "app.trip.result-listener-enabled=true",
        "app.trip.worker-initial-delay-ms=3600000", "spring.kafka.admin.auto-create=true"
})
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TripConcurrentContextsIntegrationTest.Topics.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TripConcurrentContextsIntegrationTest {
    private static final String RUN = "contexts-" + UUID.randomUUID();
    private static final String RESULT_TOPIC = RUN + "-result";
    private static final String GROUP = RUN + "-consumer";

    @Autowired private ConfigurableApplicationContext first;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JdbcConnectionDetails database;
    @Autowired private ObjectMapper json;
    @Autowired private KafkaTemplate<String, String> kafka;

    private ConfigurableApplicationContext second;
    private TripAnalysisTestFixture fixture;
    private Long ownerId;
    private Long tripId;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("app.trip.edit-lock.namespace", () -> RUN);
        registry.add("app.kafka.topics.trip-update-result", () -> RESULT_TOPIC);
        registry.add("app.kafka.dead-letter-topics.trip-update-result", () -> RESULT_TOPIC + "-dlt");
        registry.add("KAFKA_TRIP_UPDATE_RESULT_CONSUMER_GROUP", () -> GROUP);
    }

    @BeforeAll
    void startIndependentServer() {
        second = startServer();
        assertThat(second.getBean(DataSource.class)).isNotSameAs(first.getBean(DataSource.class));
        assertThat(redis(second)).isNotSameAs(redis(first));
        assertThat(second.getBean(JdbcConnectionDetails.class).getJdbcUrl()).isEqualTo(database.getJdbcUrl());
        assertThat(second.getBean(DataRedisConnectionDetails.class).getStandalone())
                .isEqualTo(first.getBean(DataRedisConnectionDetails.class).getStandalone());
        awaitAssignment(first);
        awaitAssignment(second);
    }

    @BeforeEach
    void prepareTrip() {
        fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
    }

    @AfterEach
    void isolateClaims() {
        jdbc.update("UPDATE trip_analysis_executions SET result_next_attempt_at = '2099-01-01' WHERE trip_id = ?", tripId);
    }

    @AfterAll
    void closeIndependentServer() {
        if (second != null) {
            second.close();
        }
    }

    @Test
    void 두_서버의_시작과_편집과_중복_결과가_경쟁해도_확정_입력과_정산은_하나다() throws Exception {
        Long normal = fixture.createPhoto(tripId, ownerId);
        Addition included = fixture.prepareAddition(tripId, ownerId);
        race(() -> scheduling(first).startNext(tripId), () -> scheduling(second).startNext(tripId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_analysis_executions WHERE trip_id = ? AND ended_at IS NULL",
                Integer.class, tripId)).isOne();
        String executionId = jdbc.queryForObject("SELECT execution_id FROM trip_analysis_executions WHERE trip_id = ?",
                String.class, tripId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM additional_attachment_upload_outbox WHERE analysis_execution_id = ? AND message_type = 'process'",
                Integer.class, executionId)).isOne();
        String frozen = input(executionId);
        assertThat(admit(second, tripId)).isTrue();
        Addition later = fixture.prepareAddition(tripId, ownerId);
        race(() -> {
            second.getBean(TripUpdateService.class).updateTrip(tripId, ownerId,
                    new TripUpdateRequest("최신여행", null, LocalDate.of(2026, 9, 4), null));
            return true;
        }, () -> {
            first.getBean(TripPhotoDeletionService.class).deleteOne(ownerId, normal);
            return true;
        });
        sendSuccess(executionId);
        sendSuccess(executionId);
        TripAnalysisResultClaim claim = results(first).claim().orElseThrow();
        List<Boolean> applied = race(() -> complete(first, claim), () -> complete(second, claim));
        assertThat(applied).containsExactlyInAnyOrder(true, false);
        assertThat(state(executionId)).isEqualTo("COMPLETED");
        assertThat(input(executionId)).isEqualTo(frozen);
        assertThat(jdbc.queryForObject("SELECT trip_name FROM trips WHERE trip_id = ?", String.class, tripId)).isEqualTo("최신여행");
        assertThat(jdbc.queryForObject("SELECT end_date FROM trips WHERE trip_id = ?", String.class, tripId)).isEqualTo("2026-09-04");
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM trip_attachments WHERE trip_attachment_id = ?",
                Boolean.class, normal)).isTrue();
        assertThat(issue(included.photoId())).isEqualTo("UNCLEAR_LOCATION");
        assertThat(issue(later.photoId())).isEqualTo("NONE");
        Map<String, Object> settled = jdbc.queryForMap("SELECT * FROM user_stats WHERE user_id = ?", ownerId);
        sendSuccess(executionId);
        assertThat(results(second).claim()).isEmpty();
        assertThat(jdbc.queryForMap("SELECT * FROM user_stats WHERE user_id = ?", ownerId)).isEqualTo(settled);
        assertThat(scheduling(second).startNext(tripId)).isPresent();
    }

    @Test
    void 저장_실패는_함께_롤백되고_재시작한_서버가_lease를_인계받아_한번만_완료한다() throws Exception {
        Long normal = fixture.createPhoto(tripId, ownerId);
        Addition addition = fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling(first).startNext(tripId).orElseThrow();
        sendSuccess(executionId);
        first.getBean(TripPhotoDeletionService.class).deleteOne(ownerId, normal);
        TripAnalysisResultClaim previous = results(second).claim().orElseThrow();
        Map<String, Object> stats = jdbc.queryForMap("SELECT * FROM user_stats WHERE user_id = ?", ownerId);
        int notifications = notificationCount();
        String trigger = "reject_context_completion_" + tripId;
        executeRoot("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON trip_analysis_executions FOR EACH ROW "
                + "BEGIN IF NEW.trip_id = " + tripId + " AND NEW.status = 'COMPLETED' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '"
                + TripUploadTestErrorMessage.ANALYSIS_OUTBOX_FAILURE.message() + "'; END IF; END");
        try {
            assertThatThrownBy(() -> complete(first, previous)).hasRootCauseInstanceOf(SQLException.class);
            assertThat(state(executionId)).isEqualTo("RESULT_APPLYING");
            assertThat(issue(addition.photoId())).isEqualTo("NONE");
            assertThat(jdbc.queryForObject("SELECT status FROM trip_photo_deletion_reservations WHERE attachment_id = ?",
                    String.class, normal)).isEqualTo("PENDING");
            assertThat(jdbc.queryForMap("SELECT * FROM user_stats WHERE user_id = ?", ownerId)).isEqualTo(stats);
            assertThat(notificationCount()).isEqualTo(notifications);
        } finally {
            executeRoot("DROP TRIGGER " + trigger);
        }
        restartSecond();
        jdbc.update("UPDATE trip_analysis_executions SET result_lease_until = '2000-01-01' WHERE execution_id = ?", executionId);
        TripAnalysisResultClaim current = results(second).claim().orElseThrow();
        assertThat(current.claimToken()).isNotEqualTo(previous.claimToken());
        assertThat(complete(first, previous)).isFalse();
        assertThat(complete(second, current)).isTrue();
        assertThat(state(executionId)).isEqualTo("COMPLETED");
        assertThat(notificationCount()).isGreaterThan(notifications);
    }

    @Test
    void 다른_여행의_용량_예약도_두_서버가_소유자_전체_한도를_넘길_수_없다() throws Exception {
        jdbc.update("UPDATE user_stats SET storage_maximum_bytes = 100 WHERE user_id = ?", ownerId);
        jdbc.update("INSERT INTO trips (user_id, trip_name, start_date, end_date, processing_status) VALUES (?, '용량경쟁', '2026-09-01', '2026-09-02', 'COMPLETED')", ownerId);
        Long another = jdbc.queryForObject("SELECT trip_id FROM trips WHERE user_id = ? AND trip_name = '용량경쟁'", Long.class, ownerId);
        List<Boolean> accepted = race(() -> admit(first, tripId), () -> admit(second, another));
        assertThat(accepted).containsExactlyInAnyOrder(true, false);
        assertThat(jdbc.queryForObject("SELECT storage_reserved_bytes FROM user_stats WHERE user_id = ?", Long.class, ownerId))
                .isEqualTo(60L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM additional_attachment_executions WHERE user_id = ?", Integer.class, ownerId))
                .isOne();
    }

    @Test
    void 한_서버의_Redis_연결이_끊겨도_DB로_우회하지_않고_다른_서버는_정상_처리한다() {
        redis(second).shutdown();
        try {
            assertThatThrownBy(() -> second.getBean(TripUpdateService.class).updateTrip(tripId, ownerId,
                    new TripUpdateRequest("실패할수정", null, null, null)))
                    .isInstanceOf(TripEditLockRejectedException.class);
            assertThat(jdbc.queryForObject("SELECT trip_name FROM trips WHERE trip_id = ?", String.class, tripId)).isEqualTo("분석여행");
            first.getBean(TripUpdateService.class).updateTrip(tripId, ownerId,
                    new TripUpdateRequest("정상수정", null, null, null));
            assertThat(jdbc.queryForObject("SELECT trip_name FROM trips WHERE trip_id = ?", String.class, tripId)).isEqualTo("정상수정");
        } finally {
            restartSecond();
        }
    }

    private ConfigurableApplicationContext startServer() {
        SpringApplication application = new SpringApplication(YeodamBeApplication.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.setRegisterShutdownHook(false);
        application.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("sharedMysqlConnectionDetails", database);
            context.getBeanFactory().registerSingleton("sharedRedisConnectionDetails", first.getBean(DataRedisConnectionDetails.class));
            context.getBeanFactory().registerSingleton("sharedKafkaConnectionDetails", first.getBean(KafkaConnectionDetails.class));
            context.getBeanFactory().registerSingleton("sharedMongoConnectionDetails", first.getBean(MongoConnectionDetails.class));
        });
        return application.run("--spring.profiles.active=test", "--spring.config.import=", "--server.address=127.0.0.1",
                "--server.port=0", "--spring.datasource.hikari.maximum-pool-size=3", "--app.trip.edit-lock.enabled=true",
                "--app.trip.edit-lock.namespace=" + RUN, "--app.trip.result-listener-enabled=true",
                "--app.trip.worker-initial-delay-ms=3600000", "--app.kafka.topics.trip-update-result=" + RESULT_TOPIC,
                "--app.kafka.dead-letter-topics.trip-update-result=" + RESULT_TOPIC + "-dlt",
                "--KAFKA_TRIP_UPDATE_RESULT_CONSUMER_GROUP=" + GROUP);
    }

    private void restartSecond() {
        second.close();
        second = startServer();
        awaitAssignment(first);
        awaitAssignment(second);
    }

    private void awaitAssignment(ConfigurableApplicationContext context) {
        await().atMost(Duration.ofSeconds(30)).until(() -> context.getBean(KafkaListenerEndpointRegistry.class)
                .getAllListenerContainers().stream()
                .anyMatch(container -> container.getAssignedPartitions() != null
                        && container.getAssignedPartitions().stream().anyMatch(partition -> partition.topic().equals(RESULT_TOPIC))));
    }

    private boolean admit(ConfigurableApplicationContext context, Long targetTrip) {
        AdditionalAttachmentUploadUrlRequest request = new AdditionalAttachmentUploadUrlRequest(
                UUID.randomUUID().toString(), 1, 1, true,
                List.of(new AdditionalAttachmentUploadUrlRequest.Attachment("photo.jpg", "image/jpeg", 60L)));
        try {
            context.getBean(TripMutationCoordinator.class).execute(targetTrip,
                    () -> context.getBean(AdditionalAttachmentAdmissionTransactionService.class).prepareBatch(targetTrip, ownerId, request));
            return true;
        } catch (AttachmentUploadLimitExceededException failure) {
            return false;
        }
    }

    private boolean complete(ConfigurableApplicationContext context, TripAnalysisResultClaim claim) {
        try {
            results(context).complete(claim, results(context).validate(claim), Map.of());
            return true;
        } catch (CancellationException failure) {
            return false;
        }
    }

    private void sendSuccess(String executionId) throws Exception {
        List<Map<String, Object>> photos = StreamSupport.stream(json.readTree(input(executionId)).path("attachments").spliterator(), false)
                .map(photo -> Map.<String, Object>of("trip_attachment_id", photo.path("trip_attachment_id").asLong(),
                        "region_origin", "UNKNOWN", "issue", "UNCLEAR_LOCATION")).toList();
        JsonNode result = json.valueToTree(Map.of("places", List.of(), "failed", List.of(), "unclassified", photos));
        String raw = json.writeValueAsString(new TripCreateResultEvent("process_result", tripId, executionId,
                TripCreateResultEvent.Status.COMPLETED, result, null));
        RecordMetadata sent = kafka.send(RESULT_TOPIC, tripId.toString(), raw).get(10, TimeUnit.SECONDS).getRecordMetadata();
        TopicPartition partition = new TopicPartition(sent.topic(), sent.partition());
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers",
                String.join(",", first.getBean(KafkaConnectionDetails.class).getBootstrapServers())))) {
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                OffsetAndMetadata committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS).get(partition);
                return committed != null && committed.offset() > sent.offset();
            });
        }
        assertThat(jdbc.queryForObject("SELECT result_raw_payload FROM trip_analysis_executions WHERE execution_id = ?",
                String.class, executionId)).isEqualTo(raw);
    }

    private <T> List<T> race(Callable<T> left, Callable<T> right) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<T> firstResult = workers.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return left.call();
            });
            Future<T> secondResult = workers.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return right.call();
            });
            start.countDown();
            return List.of(firstResult.get(20, TimeUnit.SECONDS), secondResult.get(20, TimeUnit.SECONDS));
        }
    }

    private String input(String executionId) {
        return jdbc.queryForObject("SELECT payload FROM additional_attachment_upload_outbox WHERE analysis_execution_id = ? AND message_type = 'process'",
                String.class, executionId);
    }

    private String state(String executionId) {
        return jdbc.queryForObject("SELECT status FROM trip_analysis_executions WHERE execution_id = ?", String.class, executionId);
    }

    private String issue(Long photoId) {
        return jdbc.queryForObject("SELECT issue FROM trip_attachments WHERE trip_attachment_id = ?", String.class, photoId);
    }

    private int notificationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trip_edit_notification_outbox WHERE target_type = 'TRIP' AND target_id = ?",
                Integer.class, tripId);
    }

    private void executeRoot(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(database.getJdbcUrl(), "root", database.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private RedissonClient redis(ConfigurableApplicationContext context) {
        return context.getBean("tripEditRedissonClient", RedissonClient.class);
    }

    private TripAnalysisExecutionService scheduling(ConfigurableApplicationContext context) {
        return context.getBean(TripAnalysisExecutionService.class);
    }

    private TripAnalysisResultApplicationService results(ConfigurableApplicationContext context) {
        return context.getBean(TripAnalysisResultApplicationService.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {
        @Bean
        NewTopic concurrentResults() {
            return new NewTopic(RESULT_TOPIC, 2, (short) 1);
        }

        @Bean
        NewTopic concurrentResultsDlt() {
            return new NewTopic(RESULT_TOPIC + "-dlt", 2, (short) 1);
        }
    }
}
