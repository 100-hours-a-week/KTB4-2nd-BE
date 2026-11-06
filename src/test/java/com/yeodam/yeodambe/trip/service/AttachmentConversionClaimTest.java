package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.client.InvalidAttachmentConversionMessageException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.repository.AttachmentConversionTaskRepository;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaimResult;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties.Topics;
import tools.jackson.core.type.TypeReference;

import static com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaimResult.Status.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest(properties = "spring.application.name=conversion-claim-test")
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AttachmentConversionClaimTest.DatabaseTimeConfig.class})
@Transactional
class AttachmentConversionClaimTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2000, 1, 1, 12, 0);
    @Autowired private AttachmentConversionTransactionService transactions;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper json;
    @Autowired private TestJdbcTemplate jdbc;
    @MockitoSpyBean private AttachmentConversionTaskRepository tasks;

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseTimeConfig {
        @Bean
        TestJdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new TestJdbcTemplate(dataSource);
        }
    }

    static class TestJdbcTemplate extends JdbcTemplate {
        private final AtomicReference<LocalDateTime> databaseTime = new AtomicReference<>(NOW);

        TestJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        void setDatabaseTime(LocalDateTime value) {
            databaseTime.set(value);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            if ("SELECT CURRENT_TIMESTAMP(6)".equals(sql) && requiredType == LocalDateTime.class) {
                return requiredType.cast(databaseTime.get());
            }
            return super.queryForObject(sql, requiredType);
        }
    }

    @BeforeEach
    void fixedDatabaseTime() {
        jdbc.setDatabaseTime(NOW);
    }

    @Test
    void DB_시각_변경은_다른_조회_결과를_바꾸지_않는다() throws Exception {
        jdbc.setDatabaseTime(NOW.plusMinutes(1));
        Integer first = CompletableFuture.supplyAsync(() -> jdbc.queryForObject("SELECT 1", Integer.class))
                .get(5, TimeUnit.SECONDS);
        Integer second = CompletableFuture.supplyAsync(() -> jdbc.queryForObject("SELECT 1", Integer.class))
                .get(5, TimeUnit.SECONDS);
        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class))
                .isEqualTo(NOW.plusMinutes(1));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 두_노드가_동시에_요청해도_한_번만_선점한다(Kind kind) throws Exception {
        var fixture = new TransactionTemplate(transactionManager).execute(status -> fixture(kind));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = List.of(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return transactions.claim(fixture.taskId(), kind, Lane.LIGHT);
            }), pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return transactions.claim(fixture.taskId(), kind, Lane.LIGHT);
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var actual = List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS));
            assertThat(actual).filteredOn(result -> result.status() == CLAIMED).hasSize(1);
            assertThat(actual).filteredOn(result -> result.status() == RETRY_LATER).singleElement()
                    .satisfies(result -> assertThat(result.retryAt()).isEqualTo(NOW.plusMinutes(10)));
            var claim = actual.stream().filter(result -> result.status() == CLAIMED).findFirst().orElseThrow().claim();
            assertThat(claim.claimToken()).isEqualTo(jdbc.queryForObject(
                    "SELECT claim_token FROM attachment_conversion_tasks WHERE task_id = ?", String.class, fixture.taskId()));
            assertThat(claim.photos()).singleElement().satisfies(photo -> {
                assertThat(photo.itemId()).isEqualTo(fixture.lightItemId());
                assertThat(photo.objectKey()).isEqualTo("original/" + fixture.uploadId() + "/light");
                assertThat(photo.attempts()).isZero();
            });
            assertThat(claim.kind()).isEqualTo(kind);
            assertThat(claim.lane()).isEqualTo(Lane.LIGHT);
            assertThat(claim.batchId()).isEqualTo(fixture.batchId());
            assertThat(claim.userId()).isEqualTo(fixture.userId());
            assertThat(claim.tripId()).isEqualTo(fixture.tripId());
            assertThat(claim.requestId()).isEqualTo("request-before-worker");
            assertAttemptsUnchanged(fixture);
        }
    }

    @ParameterizedTest
    @CsvSource({"INITIAL,false", "ADDITIONAL,false", "INITIAL,true", "ADDITIONAL,true"})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 소유권을_잃은_토큰은_새_소유자를_해제하지_않고_포기한_시도만_기록한다(Kind kind, boolean nextBegan) {
        var fixture = new TransactionTemplate(transactionManager).execute(status -> fixture(kind));
        var old = claimed(fixture);
        var first = transactions.beginPhoto(old, fixture.lightItemId()).attempt();
        jdbc.update("UPDATE attachment_conversion_tasks SET lease_until = ? WHERE task_id = ?", NOW, fixture.taskId());
        var current = claimed(fixture);
        AttachmentConversionClaim.Attempt second = nextBegan ? transactions.beginPhoto(current, fixture.lightItemId()).attempt() : null;
        jdbc.setDatabaseTime(NOW.plusMinutes(1));
        assertThat(transactions.releaseClaim(old)).isFalse();
        assertThat(jdbc.queryForObject("SELECT claim_token FROM attachment_conversion_tasks WHERE task_id = ?",
                String.class, fixture.taskId())).isEqualTo(current.claimToken());
        assertThat(jdbc.queryForObject("SELECT lease_until FROM attachment_conversion_tasks WHERE task_id = ?",
                LocalDateTime.class, fixture.taskId())).isEqualTo(NOW.plusMinutes(10));
        List<CleanupProbe> journal = json.readValue(jdbc.queryForObject("SELECT cleanup_json FROM " + itemTable(kind)
                + " WHERE upload_item_id = ?", String.class, fixture.lightItemId()), new TypeReference<List<CleanupProbe>>() {});
        var oldKeys = List.of(first.plannedKeys().analyzeKey(), first.plannedKeys().previewKey());
        assertThat(journal).filteredOn(object -> oldKeys.contains(object.key())).hasSize(2)
                .allSatisfy(object -> assertThat(object.startedAt()).isEqualTo(nextBegan ? NOW : NOW.plusMinutes(1)));
        if (second != null) {
            var activeKeys = List.of(second.plannedKeys().analyzeKey(), second.plannedKeys().previewKey());
            assertThat(journal).filteredOn(object -> activeKeys.contains(object.key())).hasSize(2)
                    .allSatisfy(object -> assertThat(object.startedAt()).isNull());
        }
        assertThat(transactions.renewLease(current)).isTrue();
        assertThat(jdbc.queryForObject("SELECT conversion_attempts FROM " + itemTable(kind) + " WHERE upload_item_id = ?",
                Integer.class, fixture.lightItemId())).isEqualTo(nextBegan ? 2 : 1);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 선점_해제는_활성_파생_파일_키를_정리_예약하지_않는다(Kind kind) {
        var fixture = fixture(kind);
        var claim = claimed(fixture);
        var attempt = transactions.beginPhoto(claim, fixture.lightItemId()).attempt();
        var planned = attempt.plannedKeys();
        var file = StoredFile.uploaded(fixture.userId(), "active.jpg", planned.originalKey(), "image/jpeg");
        entityManager.persist(file);
        var attachment = TripAttachment.initial(fixture.tripId(), file.getId(), planned.analyzeKey(), planned.previewKey(), null);
        entityManager.persist(attachment);
        entityManager.flush();
        jdbc.update("UPDATE " + itemTable(kind) + " SET trip_attachment_id = ?, derived_json = ? WHERE upload_item_id = ?",
                attachment.getId(), json.writeValueAsString(planned), fixture.lightItemId());
        entityManager.clear();
        assertThat(transactions.releaseClaim(claim)).isTrue();
        List<CleanupProbe> journal = json.readValue(jdbc.queryForObject("SELECT cleanup_json FROM " + itemTable(kind)
                + " WHERE upload_item_id = ?", String.class, fixture.lightItemId()), new TypeReference<List<CleanupProbe>>() {});
        assertThat(journal).hasSize(3).allSatisfy(object -> assertThat(object.startedAt()).isNull());
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 부분_사진의_선점_회수는_다음_시작_호출_없이_보존을_시작한다(Kind kind) throws Exception {
        var fixture = new TransactionTemplate(transactionManager).execute(status -> fixture(kind));
        var processing = mock(TripAttachmentDerivativeService.class);
        var photo = new CompletableFuture<DerivedPhotoKeys>();
        var started = new CountDownLatch(1);
        var observed = new AtomicReference<AttachmentConversionClaim>();
        var attempt = new AtomicReference<AttachmentConversionClaim.Attempt>();
        when(processing.processConversionPhoto(any(), any(), any(), any())).thenAnswer(call -> {
            observed.set(call.getArgument(0));
            attempt.set(call.getArgument(2));
            started.countDown();
            return photo;
        });
        var topics = new Topics(null, null, null, null);
        String topic = kind == Kind.INITIAL ? topics.initialLight() : topics.additionalLight();
        var worker = new AttachmentConversionWorker(transactions, processing, topics, Duration.ofMillis(50));
        try {
            var ack = worker.process(fixture.taskId(), kind, Lane.LIGHT, topic, 0);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var cleanup = worker.revoke(topic, java.util.Set.of(0));
            assertThat(cleanup).isNotDone();
            // Task 7 Future 종료는 부분 PUT 및 입력 임시 파일 정리가 끝난 신호다.
            photo.completeExceptionally(new CancellationException("partial output fenced"));
            cleanup.get(10, TimeUnit.SECONDS);
            assertThat(ack).isNotDone();
            var keys = List.of(attempt.get().plannedKeys().analyzeKey(), attempt.get().plannedKeys().previewKey());
            String query = "SELECT cleanup_json FROM " + itemTable(kind) + " WHERE upload_item_id = ?";
            String firstJournal = jdbc.queryForObject(query, String.class, fixture.lightItemId());
            List<CleanupProbe> journal = json.readValue(firstJournal,
                    new TypeReference<List<CleanupProbe>>() {});
            assertThat(journal).filteredOn(object -> keys.contains(object.key())).hasSize(2).allSatisfy(object -> {
                assertThat(object.startedAt()).isEqualTo(NOW);
                assertThat(object.deleted()).isFalse();
            });
            assertThat(journal).filteredOn(object -> !keys.contains(object.key())).singleElement()
                    .satisfies(object -> assertThat(object.startedAt()).isNull());
            jdbc.setDatabaseTime(NOW.plusDays(2));
            assertThat(transactions.releaseClaim(observed.get())).isFalse();
            assertThat(jdbc.queryForObject(query, String.class, fixture.lightItemId())).isEqualTo(firstJournal);
            assertThat(jdbc.queryForObject("SELECT conversion_attempts FROM " + itemTable(kind) + " WHERE upload_item_id = ?",
                    Integer.class, fixture.lightItemId())).isEqualTo(1);
        } finally {
            photo.completeExceptionally(new CancellationException());
            worker.drain().get(10, TimeUnit.SECONDS);
            worker.close();
        }
    }

    private record CleanupProbe(
            String key,
            LocalDateTime startedAt,
            String reason,
            boolean deleted
    ) {
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 현재_소유자는_기존_임대_만료_전에도_종료된_작업의_선점을_해제한다(Kind kind) {
        var fixture = fixture(kind);
        var claim = claimed(fixture);
        entityManager.flush();
        jdbc.update("UPDATE attachment_conversion_tasks SET status = 'SUCCEEDED' WHERE task_id = ?", claim.taskId());
        entityManager.clear();
        assertThat(transactions.releaseClaim(claim)).isTrue();
        assertThat(transactions.claim(claim.taskId(), kind, Lane.LIGHT).status()).isEqualTo(ACK_TERMINAL);
        assertAttemptsUnchanged(fixture);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 현재_토큰만_해제하고_시도를_유지하며_즉시_재선점할_수_있다(Kind kind) {
        var fixture = fixture(kind);
        var first = claimed(fixture);
        var attempt = transactions.beginPhoto(first, fixture.lightItemId()).attempt();
        assertThat(attempt.attemptNo()).isEqualTo(1);
        assertThat(transactions.releaseClaim(first)).isTrue();
        var second = claimed(fixture);
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(transactions.releaseClaim(first)).isFalse();
        assertThat(transactions.renewLease(second)).isTrue();
        assertThat(second.photos().getFirst().attempts()).isEqualTo(1);
        assertThat(second.photos().getFirst().derived()).isNull();
        assertThat(transactions.releaseClaim(second)).isTrue();
        entityManager.flush();
        var task = tasks.findById(first.taskId()).orElseThrow();
        assertThat(task.getClaimToken()).isNull();
        assertThat(task.getLeaseUntil()).isNull();
        assertThat(task.getStatus()).isEqualTo(AttachmentConversionTask.Status.WAITING);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 만료된_토큰은_갱신하거나_저장할_수_없다(Kind kind) {
        var fixture = fixture(kind);
        var first = claimed(fixture);
        entityManager.flush();
        jdbc.update("UPDATE attachment_conversion_tasks SET lease_until = ? WHERE task_id = ?", NOW, fixture.taskId());
        entityManager.clear();

        assertThat(transactions.renewLease(first)).isFalse();
        var second = claimed(fixture);
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());
        jdbc.setDatabaseTime(NOW.plusMinutes(1));
        assertThat(transactions.renewLease(first)).isFalse();
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT lease_until FROM attachment_conversion_tasks WHERE task_id = ?",
                LocalDateTime.class, fixture.taskId())).isEqualTo(NOW.plusMinutes(10));
        assertThat(transactions.renewLease(second)).isTrue();
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT lease_until FROM attachment_conversion_tasks WHERE task_id = ?",
                LocalDateTime.class, fixture.taskId())).isEqualTo(NOW.plusMinutes(11));
        assertAttemptsUnchanged(fixture);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 토픽의_종류나_레인이_다르면_잘못된_메시지로_거부한다(Kind kind) {
        var fixture = fixture(kind);
        assertThatThrownBy(() -> transactions.claim(fixture.taskId(), kind, Lane.HEIC))
                .isInstanceOf(InvalidAttachmentConversionMessageException.class);
        assertThatThrownBy(() -> transactions.claim(fixture.taskId(), kind == Kind.INITIAL ? Kind.ADDITIONAL : Kind.INITIAL, Lane.LIGHT))
                .isInstanceOf(InvalidAttachmentConversionMessageException.class);
        assertThatThrownBy(() -> transactions.claim(UUID.randomUUID().toString(), kind, Lane.LIGHT))
                .isInstanceOf(InvalidAttachmentConversionMessageException.class);
        assertThat(tasks.findById(fixture.taskId()).orElseThrow().getClaimToken()).isNull();
        assertAttemptsUnchanged(fixture);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void DB_실패를_종료_ACK로_처리하지_않는다(Kind kind) {
        var fixture = fixture(kind);
        var failure = new TransientDataAccessResourceException("test DB unavailable");
        doThrow(failure).when(tasks).findById(fixture.taskId());
        assertThatThrownBy(() -> transactions.claim(fixture.taskId(), kind, Lane.LIGHT)).isSameAs(failure);
        assertThat(jdbc.queryForObject("SELECT claim_token FROM attachment_conversion_tasks WHERE task_id = ?",
                String.class, fixture.taskId())).isNull();
    }

    @ParameterizedTest
    @CsvSource({"INITIAL,SUCCEEDED", "INITIAL,CANCELED", "INITIAL,FAILED", "ADDITIONAL,SUCCEEDED", "ADDITIONAL,CANCELED", "ADDITIONAL,FAILED"})
    void 종료된_작업은_변환_없이_ACK할_수_있다(Kind kind, String state) {
        var fixture = fixture(kind);
        jdbc.update("UPDATE attachment_conversion_tasks SET status = ? WHERE task_id = ?", state, fixture.taskId());
        entityManager.clear();
        var result = transactions.claim(fixture.taskId(), kind, Lane.LIGHT);
        assertThat(result.status()).isEqualTo(ACK_TERMINAL);
        assertThat(result.claim()).isNull();
        assertThat(result.retryAt()).isNull();
        assertAttemptsUnchanged(fixture);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 활성_임대와_재시도_시각이_모두_도래할_때까지_대기한다(Kind kind) {
        var fixture = fixture(kind);
        jdbc.update("UPDATE attachment_conversion_tasks SET status = 'RUNNING', claim_token = 'other', lease_until = ?, next_attempt_at = ? WHERE task_id = ?",
                NOW.plusMinutes(3), NOW.plusMinutes(5), fixture.taskId());
        entityManager.clear();
        var waiting = transactions.claim(fixture.taskId(), kind, Lane.LIGHT);
        assertThat(waiting.status()).isEqualTo(RETRY_LATER);
        assertThat(waiting.claim()).isNull();
        assertThat(waiting.retryAt()).isEqualTo(NOW.plusMinutes(5));
        jdbc.update("UPDATE attachment_conversion_tasks SET lease_until = ?, next_attempt_at = ? WHERE task_id = ?", NOW, NOW, fixture.taskId());
        entityManager.clear();
        assertThat(transactions.claim(fixture.taskId(), kind, Lane.LIGHT).status()).isEqualTo(CLAIMED);
        assertAttemptsUnchanged(fixture);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 부모_경로가_DB나_HOLD이면_잘못된_메시지로_보지_않고_대기한다(Kind kind) {
        var fixture = fixture(kind);
        for (String route : List.of("DB", "HOLD")) {
            jdbc.update("UPDATE " + batchTable(kind) + " SET conversion_route = ? WHERE upload_batch_id = ?", route, fixture.batchId());
            entityManager.clear();
            assertThat(transactions.claim(fixture.taskId(), kind, Lane.LIGHT).status()).isEqualTo(RETRY_LATER);
            assertThat(tasks.findById(fixture.taskId()).orElseThrow().getClaimToken()).isNull();
        }
        assertAttemptsUnchanged(fixture);
    }

    @ParameterizedTest
    @CsvSource({"INITIAL,COMPLETED,SUCCEEDED", "INITIAL,CANCELED,CANCELED", "INITIAL,FAILED,FAILED", "ADDITIONAL,COMPLETED,SUCCEEDED", "ADDITIONAL,CANCELED,CANCELED", "ADDITIONAL,FAILED,FAILED"})
    void 부모가_종료되면_사진을_시작하지_않고_작업을_정리한다(Kind kind, String parentStatus, String expectedTaskStatus) {
        var fixture = fixture(kind);
        jdbc.update("UPDATE " + executionTable(kind) + " SET status = ?, ended_at = ? WHERE execution_id = ?", parentStatus, NOW, fixture.executionId());
        entityManager.clear();
        assertThat(transactions.claim(fixture.taskId(), kind, Lane.LIGHT).status()).isEqualTo(ACK_TERMINAL);
        entityManager.flush();
        assertThat(tasks.findById(fixture.taskId()).orElseThrow().getStatus().name()).isEqualTo(expectedTaskStatus);
        assertAttemptsUnchanged(fixture);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 변환된_레인도_부모가_원자적으로_완료될_때까지_선점을_유지한다(Kind kind) {
        var fixture = fixture(kind);
        var derived = new DerivedPhotoKeys("original/" + fixture.uploadId() + "/light", "analyze", "preview", null,
                null, null, null, null, 100L, 50L, 25L, null);
        jdbc.update("UPDATE " + itemTable(kind) + " SET derived_json = ? WHERE upload_item_id = ?", json.writeValueAsString(derived), fixture.lightItemId());
        entityManager.clear();
        var result = transactions.claim(fixture.taskId(), kind, Lane.LIGHT);
        assertThat(result.status()).isEqualTo(CLAIMED);
        assertThat(result.claim().photos()).singleElement().satisfies(photo -> assertThat(photo.derived()).isEqualTo(derived));
        entityManager.flush();
        var task = tasks.findById(fixture.taskId()).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(AttachmentConversionTask.Status.RUNNING);
        assertThat(task.getRemainingPhotos()).isZero();
        assertThat(task.getRemainingBytes()).isZero();
        assertThat(jdbc.queryForObject("SELECT ended_at FROM " + executionTable(kind) + " WHERE execution_id = ?",
                LocalDateTime.class, fixture.executionId())).isNull();
        assertAttemptsUnchanged(fixture);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 삭제된_여행의_임대_갱신을_거부한다(Kind kind) {
        var fixture = fixture(kind);
        var claim = claimed(fixture);
        entityManager.flush();
        jdbc.update("UPDATE trips SET deleted_at = ? WHERE trip_id = ?", NOW, fixture.tripId());
        entityManager.clear();
        assertThat(transactions.renewLease(claim)).isFalse();
        assertAttemptsUnchanged(fixture);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void HEIC_작업은_HEIC_사진만_포함한다(Kind kind) {
        var fixture = fixture(kind);
        var task = new AttachmentConversionTask(UUID.randomUUID().toString(), kind, fixture.batchId(), fixture.tripId(), Lane.HEIC, 1, 200);
        entityManager.persist(task);
        entityManager.flush();
        var result = transactions.claim(task.getId(), kind, Lane.HEIC);
        assertThat(result.status()).isEqualTo(CLAIMED);
        assertThat(result.claim().photos()).singleElement().satisfies(photo -> {
            assertThat(photo.contentType()).isEqualTo("image/heic");
            assertThat(photo.sizeBytes()).isEqualTo(200);
        });
        assertAttemptsUnchanged(fixture);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 사진이_저장되어도_다른_활성_선점이_있으면_ACK하지_않는다(Kind kind) {
        var fixture = fixture(kind);
        claimed(fixture);
        entityManager.flush();
        jdbc.update("UPDATE " + itemTable(kind) + " SET derived_json = ? WHERE upload_item_id = ?",
                json.writeValueAsString(new DerivedPhotoKeys("original", "analyze", "preview")), fixture.lightItemId());
        entityManager.clear();
        assertThat(transactions.claim(fixture.taskId(), kind, Lane.LIGHT).status()).isEqualTo(RETRY_LATER);
        assertThat(tasks.findById(fixture.taskId()).orElseThrow().getStatus()).isEqualTo(AttachmentConversionTask.Status.RUNNING);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 위조된_부모_스냅샷으로_임대를_갱신할_수_없다(Kind kind) {
        var fixture = fixture(kind);
        var claim = claimed(fixture);
        var forged = new AttachmentConversionClaim(claim.taskId(), claim.claimToken(), claim.kind(), claim.lane(),
                claim.batchId(), claim.uploadId(), claim.executionId(), claim.tripId(), claim.userId() + 1,
                claim.requestId(), claim.photos());
        assertThat(transactions.renewLease(forged)).isFalse();
        assertThat(transactions.releaseClaim(forged)).isFalse();
        assertThat(transactions.renewLease(claim)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 갱신_중_DB_실패는_소유권_상실로_처리하지_않고_전파한다(Kind kind) {
        var fixture = fixture(kind);
        var claim = claimed(fixture);
        var failure = new TransientDataAccessResourceException("test DB unavailable");
        doThrow(failure).when(tasks).findByIdForUpdate(fixture.taskId());
        assertThatThrownBy(() -> transactions.renewLease(claim)).isSameAs(failure);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 실행이_없으면_임의의_기한을_만들지_않고_대기한다(Kind kind) {
        var fixture = fixture(kind);
        jdbc.update("DELETE FROM " + executionTable(kind) + " WHERE execution_id = ?", fixture.executionId());
        entityManager.clear();
        var result = transactions.claim(fixture.taskId(), kind, Lane.LIGHT);
        assertThat(result.status()).isEqualTo(RETRY_LATER);
        assertThat(result.retryAt()).isNull();
        assertThat(tasks.findById(fixture.taskId()).orElseThrow().getStatus()).isEqualTo(AttachmentConversionTask.Status.WAITING);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 종료된_작업도_활성_선점이_있으면_정리로_소유권을_해제할_때까지_대기한다(Kind kind) {
        var fixture = fixture(kind);
        jdbc.update("UPDATE attachment_conversion_tasks SET status = 'SUCCEEDED', claim_token = 'other', lease_until = ? WHERE task_id = ?",
                NOW.plusMinutes(10), fixture.taskId());
        entityManager.clear();
        var result = transactions.claim(fixture.taskId(), kind, Lane.LIGHT);
        assertThat(result.status()).isEqualTo(RETRY_LATER);
        assertThat(result.retryAt()).isEqualTo(NOW.plusMinutes(10));
        assertThat(result.claim()).isNull();
        assertAttemptsUnchanged(fixture);
    }

    private AttachmentConversionClaim claimed(Fixture fixture) {
        var result = transactions.claim(fixture.taskId(), fixture.kind(), Lane.LIGHT);
        assertThat(result.status()).isEqualTo(CLAIMED);
        assertThat(result.claim()).isNotNull();
        return result.claim();
    }

    private Fixture fixture(Kind kind) {
        var user = new User(UUID.randomUUID() + "@yeodam.test", "claim");
        entityManager.persist(user);
        var trip = new Trip(user.getUserId(), "claim", LocalDate.now(), LocalDate.now());
        entityManager.persist(trip);
        String executionId = UUID.randomUUID().toString();
        String uploadId = UUID.randomUUID().toString();
        Long batchId;
        Long itemId;
        if (kind == Kind.INITIAL) {
            entityManager.persist(new TripCreationExecution(executionId, trip.getId(), user.getUserId(), 2));
            var batch = new InitialAttachmentUploadBatch(uploadId, executionId, trip.getId(), user.getUserId(), 1, 2, true);
            batch.accept(NOW, AttachmentConversionRoute.KAFKA);
            entityManager.persist(batch);
            var light = new InitialAttachmentUploadItem(batch, 1, "light.jpg", "image/jpeg", 100L, "original/" + uploadId + "/light");
            entityManager.persist(light);
            entityManager.persist(new InitialAttachmentUploadItem(batch, 2, "heic.heic", "image/heic", 200L, "original/" + uploadId + "/heic"));
            batchId = batch.getId();
            itemId = light.getId();
        } else {
            jdbc.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", trip.getId());
            entityManager.refresh(trip);
            entityManager.persist(new AdditionalAttachmentExecution(executionId, trip.getId(), user.getUserId(), 2, NOW));
            var batch = new AdditionalAttachmentUploadBatch(uploadId, executionId, trip.getId(), user.getUserId(), 1, 2, true);
            batch.markVerified(AttachmentConversionRoute.KAFKA);
            entityManager.persist(batch);
            var light = new AdditionalAttachmentUploadItem(batch, 1, "light.jpg", "image/jpeg", 100L, "original/" + uploadId + "/light");
            entityManager.persist(light);
            entityManager.persist(new AdditionalAttachmentUploadItem(batch, 2, "heic.heic", "image/heic", 200L, "original/" + uploadId + "/heic"));
            batchId = batch.getId();
            itemId = light.getId();
        }
        var task = new AttachmentConversionTask(UUID.randomUUID().toString(), kind, batchId, trip.getId(), Lane.LIGHT, 1, 100);
        entityManager.persist(task);
        entityManager.persist(new AttachmentConversionOutbox(UUID.randomUUID().toString(), task.getId(), 1, trip.getId(),
                "test-conversion", "{}", "request-before-worker"));
        entityManager.flush();
        return new Fixture(kind, task.getId(), batchId, uploadId, executionId, trip.getId(), user.getUserId(), itemId);
    }

    private void assertAttemptsUnchanged(Fixture fixture) {
        assertThat(jdbc.queryForObject("SELECT SUM(conversion_attempts) FROM " + itemTable(fixture.kind()) + " WHERE upload_batch_id = ?",
                Long.class, fixture.batchId())).isZero();
    }

    private String batchTable(Kind kind) {
        return kind == Kind.INITIAL ? "initial_attachment_upload_batches" : "additional_attachment_upload_batches";
    }

    private String itemTable(Kind kind) {
        return kind == Kind.INITIAL ? "initial_attachment_upload_items" : "additional_attachment_upload_items";
    }

    private String executionTable(Kind kind) {
        return kind == Kind.INITIAL ? "trip_creation_executions" : "additional_attachment_executions";
    }

    private record Fixture(
            Kind kind,
            String taskId,
            Long batchId,
            String uploadId,
            String executionId,
            Long tripId,
            Long userId,
            Long lightItemId
    ) {
    }
}
