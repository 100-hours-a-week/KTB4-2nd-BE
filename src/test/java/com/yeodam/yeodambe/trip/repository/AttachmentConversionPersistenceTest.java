package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.SharedTestMysql;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionOutbox;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionRoute;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class AttachmentConversionPersistenceTest {

    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AttachmentConversionTaskRepository taskRepository;
    @Autowired private AttachmentConversionOutboxRepository outboxRepository;

    private Long tripId;
    private InitialAttachmentUploadBatch initial;
    private AdditionalAttachmentUploadBatch additional;

    private void prepareBatch() {
        User user = new User(UUID.randomUUID() + "@yeodam.test", "conversion");
        entityManager.persist(user);
        Trip trip = new Trip(user.getUserId(), "conversion", LocalDate.now(), LocalDate.now());
        entityManager.persist(trip);
        tripId = trip.getId();
        initial = new InitialAttachmentUploadBatch(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), tripId, user.getUserId(), 1, 2, true);
        additional = new AdditionalAttachmentUploadBatch(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), tripId, user.getUserId(), 1, 2, true);
        entityManager.persist(initial);
        entityManager.persist(additional);
        entityManager.flush();
    }

    @Test
    void 같은_배치와_레인의_조합은_유일해야_한다() {
        prepareBatch();
        for (Kind kind : Kind.values()) {
            Long batchId = kind == Kind.INITIAL ? initial.getId() : additional.getId();
            taskRepository.saveAndFlush(task(kind, batchId, Lane.LIGHT));
            assertThatThrownBy(() -> insertTask(kind == Kind.INITIAL ? batchId : null,
                    kind == Kind.ADDITIONAL ? batchId : null, kind, Lane.LIGHT, tripId))
                    .hasMessageContaining(kind == Kind.INITIAL ? "uk_conversion_initial_lane" : "uk_conversion_additional_lane");
            taskRepository.saveAndFlush(task(kind, batchId, Lane.HEIC));
        }
        assertThat(taskRepository.findAll()).hasSize(4);
        assertThat(taskRepository.findByInitialBatchIdAndLane(initial.getId(), Lane.LIGHT)).isPresent();
        assertThat(taskRepository.findByAdditionalBatchIdAndLane(additional.getId(), Lane.HEIC)).isPresent();
    }

    @Test
    void 정확히_하나의_원본_배치가_필요하다() {
        prepareBatch();
        assertThatThrownBy(() -> insertTask(null, null, Kind.INITIAL, Lane.LIGHT, tripId))
                .hasMessageContaining("ck_conversion_source_batch");
        assertThatThrownBy(() -> insertTask(initial.getId(), additional.getId(), Kind.INITIAL, Lane.LIGHT, tripId))
                .hasMessageContaining("ck_conversion_source_batch");
        assertThatThrownBy(() -> insertTask(null, additional.getId(), Kind.INITIAL, Lane.LIGHT, tripId))
                .hasMessageContaining("ck_conversion_source_batch");
    }

    @Test
    void 없는_부모는_MySQL_외래_키로_거부한다() {
        prepareBatch();
        assertThatThrownBy(() -> insertTask(Long.MAX_VALUE, null, Kind.INITIAL, Lane.LIGHT, tripId))
                .hasMessageContaining("fk_conversion_initial_batch");
        assertThatThrownBy(() -> insertTask(null, Long.MAX_VALUE, Kind.ADDITIONAL, Lane.LIGHT, tripId))
                .hasMessageContaining("fk_conversion_additional_batch");
        assertThatThrownBy(() -> insertTask(initial.getId(), null, Kind.INITIAL, Lane.LIGHT, Long.MAX_VALUE))
                .hasMessageContaining("fk_conversion_trip");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO attachment_conversion_outbox
                    (outbox_id, task_id, delivery_no, trip_id, topic, payload, request_id)
                VALUES (?, ?, 1, ?, 'yeodam-image-initial-light', '{}', 'request-1')
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), tripId))
                .hasMessageContaining("fk_conversion_outbox_task");
    }

    @Test
    void 작업을_중복하지_않고_전달을_재발급할_수_있다() {
        prepareBatch();
        AttachmentConversionTask task = taskRepository.saveAndFlush(task(Kind.INITIAL, initial.getId(), Lane.LIGHT));
        AttachmentConversionOutbox first = outboxRepository.saveAndFlush(outbox(task, 1));
        outboxRepository.saveAndFlush(outbox(task, 2));
        entityManager.clear();

        assertThat(taskRepository.findAll()).hasSize(1);
        assertThat(outboxRepository.findAll()).hasSize(2);
        AttachmentConversionOutbox loaded = outboxRepository.findById(first.getId()).orElseThrow();
        assertThat(loaded.getPublicationVersion()).isZero();
        assertThat(loaded.getStatus()).isEqualTo(AttachmentConversionOutbox.Status.PENDING);
        assertThat(loaded.getEventSource()).isEqualTo("attachment_conversion_outbox");
        assertThat(loaded.isCdcManaged()).isTrue();
        assertThat(loaded.getPayload()).isEqualTo(first.getPayload());
        assertThat(loaded.getRequestId()).isEqualTo("request-1");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO attachment_conversion_outbox
                    (outbox_id, task_id, delivery_no, trip_id, topic, payload, request_id)
                VALUES (?, ?, 1, ?, 'yeodam-image-initial-light', '{}', 'request-1')
                """, UUID.randomUUID().toString(), task.getId(), tripId))
                .hasMessageContaining("uk_conversion_outbox_delivery");
    }

    @Test
    void 선점_진행과_발행_위치를_영속화한다() {
        prepareBatch();
        AttachmentConversionTask task = task(Kind.INITIAL, initial.getId(), Lane.LIGHT);
        LocalDateTime now = LocalDateTime.of(2026, 10, 31, 10, 0);
        ReflectionTestUtils.setField(task, "claimToken", "claim-1");
        ReflectionTestUtils.setField(task, "leaseUntil", now.plusMinutes(10));
        ReflectionTestUtils.setField(task, "nextAttemptAt", now);
        ReflectionTestUtils.setField(task, "currentItemId", 23L);
        ReflectionTestUtils.setField(task, "currentAttemptNo", 2);
        ReflectionTestUtils.setField(task, "currentAttemptToken", "claim-1");
        taskRepository.saveAndFlush(task);
        AttachmentConversionOutbox outbox = outbox(task, 1);
        Instant brokerTime = Instant.parse("2026-10-31T01:00:00Z");
        ReflectionTestUtils.setField(outbox, "topicId", "topic-1");
        ReflectionTestUtils.setField(outbox, "recordPartition", 1);
        ReflectionTestUtils.setField(outbox, "recordOffset", 42L);
        ReflectionTestUtils.setField(outbox, "brokerTimestamp", brokerTime);
        ReflectionTestUtils.setField(outbox, "leaderEpoch", 3);
        outboxRepository.saveAndFlush(outbox);
        entityManager.clear();

        AttachmentConversionTask loaded = taskRepository.findByIdForUpdate(task.getId()).orElseThrow();
        assertThat(loaded.getClaimToken()).isEqualTo("claim-1");
        assertThat(loaded.getLeaseUntil()).isEqualTo(now.plusMinutes(10));
        assertThat(loaded.getNextAttemptAt()).isEqualTo(now);
        assertThat(loaded.getCurrentItemId()).isEqualTo(23L);
        assertThat(loaded.getCurrentAttemptNo()).isEqualTo(2);
        assertThat(loaded.getCurrentAttemptToken()).isEqualTo("claim-1");
        assertThat(loaded.getRemainingPhotos()).isEqualTo(2);
        assertThat(loaded.getRemainingBytes()).isEqualTo(3072);
        assertThat(loaded.getLatestDeliveryNo()).isEqualTo(1);
        assertThat(loaded.getCreatedAt()).isNotNull();
        AttachmentConversionOutbox published = outboxRepository.findById(outbox.getId()).orElseThrow();
        assertThat(published.getTopicId()).isEqualTo("topic-1");
        assertThat(published.getRecordPartition()).isEqualTo(1);
        assertThat(published.getRecordOffset()).isEqualTo(42);
        assertThat(published.getBrokerTimestamp()).isEqualTo(brokerTime);
        assertThat(published.getLeaderEpoch()).isEqualTo(3);
    }

    @Test
    void 기존_배치_경로는_엔티티와_SQL에서_DB를_기본값으로_사용한다() {
        prepareBatch();
        assertThat(initial.getConversionRoute()).isEqualTo(AttachmentConversionRoute.DB);
        assertThat(additional.getConversionRoute()).isEqualTo(AttachmentConversionRoute.DB);
        for (String table : new String[]{"initial_attachment_upload_batches", "additional_attachment_upload_batches"}) {
            assertThat(jdbc.queryForObject("SELECT conversion_route FROM " + table + " WHERE upload_batch_id = ?",
                    String.class, table.startsWith("initial") ? initial.getId() : additional.getId())).isEqualTo("DB");
            assertThat(jdbc.queryForObject("""
                    SELECT COLUMN_DEFAULT FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = ? AND column_name = 'conversion_route'
                    """, String.class, table)).isEqualTo("DB");
        }
    }

    @Test
    void 마이그레이션은_기존_배치를_보존하고_동시_삽입은_작업을_하나만_생성한다() throws Exception {
        var details = SharedTestMysql.createDatabase();
        Flyway.configure().dataSource(details.getJdbcUrl(), details.getUsername(), details.getPassword())
                .target("32").load().migrate();
        JdbcTemplate isolated = new JdbcTemplate(new DriverManagerDataSource(
                details.getJdbcUrl(), details.getUsername(), details.getPassword()));
        isolated.update("INSERT INTO users(user_id, email, nickname) VALUES(42, 'conversion@test.com', '회원')");
        isolated.update("""
                INSERT INTO trips(trip_id, user_id, trip_name, start_date, end_date)
                VALUES(73, 42, '기존여행', '2026-10-31', '2026-10-31')
                """);
        isolated.update("""
                INSERT INTO initial_attachment_upload_batches
                    (upload_batch_id, upload_id, execution_id, trip_id, user_id, batch_no,
                     total_attachment_count, is_last_batch, status)
                VALUES(11, 'initial-upload', 'initial-execution', 73, 42, 1, 1, true, 'ACCEPTED')
                """);
        isolated.update("""
                INSERT INTO additional_attachment_upload_batches
                    (upload_batch_id, upload_id, addition_id, trip_id, user_id, batch_no,
                     total_attachment_count, is_last_batch, status)
                VALUES(12, 'additional-upload', 'additional-execution', 73, 42, 1, 1, true, 'VERIFIED')
                """);
        Flyway.configure().dataSource(details.getJdbcUrl(), details.getUsername(), details.getPassword())
                .load().migrate();
        assertThat(isolated.queryForMap("""
                SELECT conversion_route, status, execution_id FROM initial_attachment_upload_batches
                """)).containsEntry("conversion_route", "DB").containsEntry("status", "ACCEPTED")
                .containsEntry("execution_id", "initial-execution");
        assertThat(isolated.queryForMap("""
                SELECT conversion_route, status, addition_id FROM additional_attachment_upload_batches
                """)).containsEntry("conversion_route", "DB").containsEntry("status", "VERIFIED")
                .containsEntry("addition_id", "additional-execution");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> insert = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("insert start timed out");
                }
                try {
                    isolated.update("""
                            INSERT INTO attachment_conversion_tasks
                                (task_id, initial_batch_id, trip_id, kind, lane,
                                 total_photos, remaining_photos, total_bytes, remaining_bytes)
                            VALUES (?, 11, 73, 'INITIAL', 'LIGHT', 1, 1, 1024, 1024)
                            """, UUID.randomUUID().toString());
                    return true;
                } catch (DuplicateKeyException duplicate) {
                    return false;
                }
            };
            var first = executor.submit(insert);
            var second = executor.submit(insert);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(isolated.queryForObject("SELECT COUNT(*) FROM attachment_conversion_tasks", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void 생성된_스키마는_DB_경로를_기본값으로_하는_배치의_직접_삽입을_허용한다() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", url)
                .applySetting("hibernate.connection.username", "sa")
                .applySetting("hibernate.connection.password", "")
                .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                .applySetting("hibernate.hbm2ddl.halt_on_error", true)
                .build();
        try (var factory = new MetadataSources(registry)
                .addAnnotatedClass(InitialAttachmentUploadBatch.class)
                .addAnnotatedClass(AdditionalAttachmentUploadBatch.class)
                .buildMetadata().buildSessionFactory()) {
            JdbcTemplate generated = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
            generated.update("""
                    INSERT INTO initial_attachment_upload_batches
                        (upload_id, execution_id, trip_id, user_id, batch_no, total_attachment_count,
                         is_last_batch, status, delivery_status, delivery_attempts, created_at, updated_at)
                    VALUES ('initial', 'execution', 1, 1, 1, 1, true, 'PENDING', 'PENDING', 0,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            generated.update("""
                    INSERT INTO additional_attachment_upload_batches
                        (upload_id, addition_id, trip_id, user_id, batch_no, total_attachment_count,
                         is_last_batch, status, created_at, updated_at)
                    VALUES ('additional', 'addition', 1, 1, 1, 1, true, 'PENDING',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            assertThat(generated.queryForObject(
                    "SELECT conversion_route FROM initial_attachment_upload_batches", String.class)).isEqualTo("DB");
            assertThat(generated.queryForObject(
                    "SELECT conversion_route FROM additional_attachment_upload_batches", String.class)).isEqualTo("DB");
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private AttachmentConversionTask task(Kind kind, Long batchId, Lane lane) {
        return new AttachmentConversionTask(UUID.randomUUID().toString(), kind, batchId, tripId, lane, 2, 3072);
    }

    private AttachmentConversionOutbox outbox(AttachmentConversionTask task, long deliveryNo) {
        return new AttachmentConversionOutbox(UUID.randomUUID().toString(), task.getId(), deliveryNo,
                tripId, "yeodam-image-initial-light", "{\"schemaVersion\":1,\"taskId\":\"" + task.getId() + "\"}", "request-1");
    }

    private void insertTask(Long initialBatchId, Long additionalBatchId, Kind kind, Lane lane, Long targetTripId) {
        jdbc.update("""
                INSERT INTO attachment_conversion_tasks
                    (task_id, initial_batch_id, additional_batch_id, trip_id, kind, lane,
                     total_photos, remaining_photos, total_bytes, remaining_bytes)
                VALUES (?, ?, ?, ?, ?, ?, 2, 2, 3072, 3072)
                """, UUID.randomUUID().toString(), initialBatchId, additionalBatchId, targetTripId, kind.name(), lane.name());
    }
}
