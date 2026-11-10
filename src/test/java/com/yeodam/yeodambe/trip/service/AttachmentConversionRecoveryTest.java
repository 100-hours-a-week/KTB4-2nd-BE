package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.repository.AttachmentConversionOutboxRepository;
import com.yeodam.yeodambe.trip.repository.AttachmentConversionTaskRepository;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionPublication;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionRecoveryCandidate;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AttachmentConversionRecoveryTest {
    private static final Duration INTERVAL = Duration.ofMinutes(5);
    private static final LocalDateTime NOW = LocalDateTime.of(2020, 1, 1, 12, 0);
    @MockitoSpyBean private AttachmentConversionTransactionService transactions;
    @Autowired
    private AttachmentConversionService conversions;
    @Autowired private AttachmentConversionTaskRepository tasks;
    @MockitoSpyBean private AttachmentConversionOutboxRepository outboxes;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM attachment_conversion_outbox");
        jdbc.update("DELETE FROM attachment_conversion_tasks");
        doReturn(NOW).when(jdbc).queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 정상_적체는_사진_변환을_반복하지_않고_재통지한다(Kind kind) {
        var fixture = fixture(kind);
        var before = protectedState(fixture);
        assertThat(transactions.renotifyIfEligible(candidate(fixture), INTERVAL)).isTrue();
        assertThat(tasks.findAll()).hasSize(1);
        assertThat(outboxes.findAll()).hasSize(2);
        var newest = outboxes.findAll().stream().filter(box -> box.getDeliveryNo() == 2).findFirst().orElseThrow();
        assertThat(newest.getStatus()).isEqualTo(AttachmentConversionOutbox.Status.PENDING);
        assertThat(newest.getPayload()).isEqualTo(fixture.outbox().getPayload());
        assertThat(newest.getRequestId()).isEqualTo(fixture.outbox().getRequestId());
        assertThat(protectedState(fixture)).isEqualTo(before);
    }

    @Test
    void 최소_간격_전에는_재통지하지_않는다() {
        var fixture = fixture(Kind.INITIAL);
        jdbc.update("UPDATE attachment_conversion_outbox SET published_at = ? WHERE outbox_id = ?",
                NOW.minus(INTERVAL).plusNanos(1000), fixture.outbox().getId());
        assertThat(transactions.findRecoveryCandidates(null, 10, INTERVAL)).isEmpty();
        assertThat(transactions.renotifyIfEligible(snapshot(fixture), INTERVAL)).isFalse();
    }

    @Test
    void 간격이_지나면_한_번만_전달한다() {
        var fixture = fixture(Kind.INITIAL);
        var expected = candidate(fixture);
        assertThat(transactions.renotifyIfEligible(expected, INTERVAL)).isTrue();
        assertThat(transactions.renotifyIfEligible(expected, INTERVAL)).isFalse();
        assertThat(transactions.findRecoveryCandidates(null, 10, INTERVAL)).isEmpty();
        assertThat(tasks.findById(fixture.taskId()).orElseThrow().getLatestDeliveryNo()).isEqualTo(2);
    }

    @Test
    void 대기중인_Outbox가_있으면_새_전달을_만들지_않는다() {
        var fixture = fixture(Kind.INITIAL);
        jdbc.update("UPDATE attachment_conversion_outbox SET status = 'PENDING' WHERE outbox_id = ?", fixture.outbox().getId());
        assertThat(transactions.findRecoveryCandidates(null, 10, INTERVAL)).isEmpty();
        assertThat(transactions.renotifyIfEligible(snapshot(fixture), INTERVAL)).isFalse();
    }

    @Test
    void 유효한_임대가_있으면_재통지하지_않는다() {
        var fixture = fixture(Kind.INITIAL);
        var expected = candidate(fixture);
        jdbc.update("UPDATE attachment_conversion_tasks SET status = 'RUNNING', claim_token = 'live', lease_until = ? WHERE task_id = ?",
                NOW.plusSeconds(1), fixture.taskId());
        assertThat(transactions.renotifyIfEligible(expected, INTERVAL)).isFalse();
        jdbc.update("UPDATE attachment_conversion_tasks SET lease_until = ? WHERE task_id = ?", NOW, fixture.taskId());
        assertThat(transactions.renotifyIfEligible(expected, INTERVAL)).isTrue();
    }

    @Test
    void 건너뛴_후보가_뒤의_적격_작업을_막지_않는다() {
        var first = fixture(Kind.INITIAL);
        var second = fixture(Kind.INITIAL);
        doReturn(false).when(transactions).renotifyIfEligible(argThat(value -> value.taskId().equals(first.taskId())), any());
        service(1, 2, 1).reconcilePendingRequests();
        assertThat(tasks.findById(second.taskId()).orElseThrow().getLatestDeliveryNo()).isEqualTo(2);
        verify(transactions, times(2)).findRecoveryCandidates(any(), eq(1), eq(INTERVAL));
    }

    @Test
    void 스캔_예산에_도달하면_페이지_조회를_멈춘다() {
        fixture(Kind.INITIAL);
        fixture(Kind.INITIAL);
        fixture(Kind.INITIAL);
        service(1, 2, 10).reconcilePendingRequests();
        verify(transactions, times(2)).findRecoveryCandidates(any(), eq(1), eq(INTERVAL));
        assertThat(outboxes.findAll()).hasSize(5);
    }

    @Test
    void 재발급_한도에_도달해도_미검사_페이지_잔여분을_보존한다() {
        fixture(Kind.INITIAL);
        var second = fixture(Kind.INITIAL);
        var recovery = service(10, 2, 1);
        recovery.reconcilePendingRequests();
        assertThat(tasks.findById(second.taskId()).orElseThrow().getLatestDeliveryNo()).isEqualTo(1);
        recovery.reconcilePendingRequests();
        assertThat(tasks.findById(second.taskId()).orElseThrow().getLatestDeliveryNo()).isEqualTo(2);
    }

    @Test
    void 다음_실행은_이어서_검사하고_끝에_도달한_다음_실행부터_처음으로_돌아간다() {
        var first = fixture(Kind.INITIAL);
        var second = fixture(Kind.INITIAL);
        doReturn(false).when(transactions).renotifyIfEligible(any(), any());
        var recovery = service(1, 1, 1);
        recovery.reconcilePendingRequests();
        recovery.reconcilePendingRequests();
        recovery.reconcilePendingRequests();
        recovery.reconcilePendingRequests();
        var order = org.mockito.Mockito.inOrder(transactions);
        order.verify(transactions).renotifyIfEligible(argThat(value -> value.taskId().equals(first.taskId())), eq(INTERVAL));
        order.verify(transactions).renotifyIfEligible(argThat(value -> value.taskId().equals(second.taskId())), eq(INTERVAL));
        order.verify(transactions).renotifyIfEligible(argThat(value -> value.taskId().equals(first.taskId())), eq(INTERVAL));
    }

    @Test
    void 재시작하면_첫_후보부터_검사한다() {
        var first = fixture(Kind.INITIAL);
        fixture(Kind.INITIAL);
        doReturn(false).when(transactions).renotifyIfEligible(any(), any());
        service(1, 1, 1).reconcilePendingRequests();
        service(1, 1, 1).reconcilePendingRequests();
        verify(transactions, times(2)).renotifyIfEligible(argThat(value -> value.taskId().equals(first.taskId())), eq(INTERVAL));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 두_노드가_동시에_재통지해도_전달은_한_개만_생성한다(Kind kind) throws Exception {
        var fixture = fixture(kind);
        var expected = candidate(fixture);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return transactions.renotifyIfEligible(expected, INTERVAL);
            });
            var second = pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return transactions.renotifyIfEligible(expected, INTERVAL);
            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(outboxes.findAll()).hasSize(2);
    }

    @Test
    void 동일_전달의_새_수신_확인은_이전_후보를_무효화한다() {
        var fixture = fixture(Kind.INITIAL);
        var expected = candidate(fixture);
        var old = fixture.outbox();
        transactions.recordPublished(new AttachmentConversionPublication(old.getId(), old.getTopic(), old.getTripId().toString(),
                old.getPayload(), "topic-id", 0, 13, Instant.ofEpochMilli(1000), 0));
        jdbc.update("UPDATE attachment_conversion_outbox SET published_at = ? WHERE outbox_id = ?", NOW.minus(INTERVAL), old.getId());
        assertThat(transactions.renotifyIfEligible(expected, INTERVAL)).isFalse();
        assertThat(transactions.renotifyIfEligible(candidate(fixture), INTERVAL)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 취소되거나_종료된_작업은_재통지하지_않는다(Kind kind) {
        var fixture = fixture(kind);
        var expected = candidate(fixture);
        jdbc.update("UPDATE " + executionTable(kind) + " SET status = 'CANCELED', ended_at = ? WHERE execution_id = ?", NOW, fixture.executionId());
        assertThat(transactions.renotifyIfEligible(expected, INTERVAL)).isFalse();
        jdbc.update("UPDATE " + executionTable(kind) + " SET status = 'COMPLETED' WHERE execution_id = ?", fixture.executionId());
        assertThat(transactions.renotifyIfEligible(expected, INTERVAL)).isFalse();
        assertThat(outboxes.findAll()).hasSize(1);
    }

    @Test
    void DB_실패시_마지막_검사_커서를_유지한다() {
        var first = fixture(Kind.INITIAL);
        var second = fixture(Kind.INITIAL);
        var third = fixture(Kind.INITIAL);
        var failed = new AtomicInteger();
        doAnswer(call -> {
            var expected = (AttachmentConversionRecoveryCandidate) call.getArgument(0);
            if (expected.taskId().equals(second.taskId()) && failed.getAndIncrement() == 0) {
                throw new TransientDataAccessResourceException("test DB failure");
            }
            return false;
        }).when(transactions).renotifyIfEligible(any(), any());
        var recovery = service(10, 1, 10);
        recovery.reconcilePendingRequests();
        recovery.reconcilePendingRequests();
        verify(transactions, times(1)).renotifyIfEligible(argThat(value -> value.taskId().equals(first.taskId())), any());
        verify(transactions, times(2)).renotifyIfEligible(argThat(value -> value.taskId().equals(second.taskId())), any());
        verify(transactions).renotifyIfEligible(argThat(value -> value.taskId().equals(third.taskId())), any());
    }

    @Test
    void 중첩_실행을_차단한다() throws Exception {
        fixture(Kind.INITIAL);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return false;
        }).when(transactions).renotifyIfEligible(any(), any());
        var recovery = service(10, 1, 10);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(recovery::reconcilePendingRequests);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            recovery.reconcilePendingRequests();
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        verify(transactions, times(1)).findRecoveryCandidates(any(), anyInt(), any());
    }

    @Test
    void DB와_HOLD와_운영_모드에서는_복구를_등록하거나_실행하지_않는다() {
        for (var route : AttachmentConversionRoute.values()) {
            var configured = properties(10, 2, 10);
            AttachmentConversionProperties properties = new AttachmentConversionProperties(
                    route,
                    configured.admission(),
                    new AttachmentConversionProperties.Operations(route == AttachmentConversionRoute.KAFKA),
                    configured.topics(),
                    configured.recovery(),
                    true,
                    true,
                    true
            );
            AttachmentConversionRecoveryService recovery = new AttachmentConversionRecoveryService(properties, conversions);
            var registrar = new org.springframework.scheduling.config.ScheduledTaskRegistrar();
            recovery.configureTasks(registrar);
            recovery.reconcilePendingRequests();
            assertThat(registrar.getFixedDelayTaskList()).isEmpty();
        }
        verify(transactions, never()).findRecoveryCandidates(any(), anyInt(), any());
    }

    @Test
    void Kafka_복구_스케줄은_동일한_최소_간격을_사용한다() {
        var registrar = new org.springframework.scheduling.config.ScheduledTaskRegistrar();
        service(10, 2, 10).configureTasks(registrar);
        assertThat(registrar.getFixedDelayTaskList()).singleElement()
                .satisfies(task -> assertThat(task.getIntervalDuration()).isEqualTo(INTERVAL));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void 경로_변경이나_작업_종료는_후보_스냅샷을_무효화한다(Kind kind) {
        var fixture = fixture(kind);
        var expected = candidate(fixture);
        String table = kind == Kind.INITIAL ? "initial_attachment_upload_batches" : "additional_attachment_upload_batches";
        for (String route : List.of("DB", "HOLD")) {
            jdbc.update("UPDATE " + table + " SET conversion_route = ? WHERE upload_batch_id = ?", route, fixture.batchId());
            assertThat(transactions.findRecoveryCandidates(null, 10, INTERVAL)).isEmpty();
            assertThat(transactions.renotifyIfEligible(expected, INTERVAL)).isFalse();
        }
        jdbc.update("UPDATE " + table + " SET conversion_route = 'KAFKA' WHERE upload_batch_id = ?", fixture.batchId());
        jdbc.update("UPDATE attachment_conversion_tasks SET status = 'SUCCEEDED' WHERE task_id = ?", fixture.taskId());
        assertThat(transactions.renotifyIfEligible(expected, INTERVAL)).isFalse();
    }

    @Test
    void 삽입_실패시_작업_상태를_유지하고_전달을_롤백한다() {
        var fixture = fixture(Kind.INITIAL);
        var expected = candidate(fixture);
        var before = protectedState(fixture);
        doThrow(new TransientDataAccessResourceException("test insert failure")).when(outboxes).save(any());
        assertThatThrownBy(() -> transactions.renotifyIfEligible(expected, INTERVAL))
                .isInstanceOf(TransientDataAccessResourceException.class);
        assertThat(tasks.findById(fixture.taskId()).orElseThrow().getLatestDeliveryNo()).isEqualTo(1);
        assertThat(outboxes.findAll()).hasSize(1);
        assertThat(protectedState(fixture)).isEqualTo(before);
    }

    @Test
    void 생성시각이_같으면_작업_ID로_키셋을_구분한다() {
        fixture(Kind.INITIAL);
        fixture(Kind.INITIAL);
        jdbc.update("UPDATE attachment_conversion_tasks SET created_at = ?", NOW);
        var page = transactions.findRecoveryCandidates(null, 1, INTERVAL);
        assertThat(page).hasSize(1);
        var first = page.getFirst();
        var next = transactions.findRecoveryCandidates(
                new AttachmentConversionRecoveryCandidate.Cursor(first.createdAt(), first.taskId()), 1, INTERVAL);
        assertThat(next).hasSize(1);
        assertThat(next.getFirst().taskId()).isGreaterThan(first.taskId());
        assertThat(transactions.findRecoveryCandidates(new AttachmentConversionRecoveryCandidate.Cursor(
                next.getFirst().createdAt(), next.getFirst().taskId()), 1, INTERVAL)).isEmpty();
    }

    @Test
    void 페이지_조회_실패시_이전에_검사한_커서를_보존한다() {
        var first = fixture(Kind.INITIAL);
        var second = fixture(Kind.INITIAL);
        doReturn(false).when(transactions).renotifyIfEligible(any(), any());
        var recovery = service(1, 1, 1);
        recovery.reconcilePendingRequests();
        doThrow(new TransientDataAccessResourceException("test query failure"))
                .doCallRealMethod().when(transactions).findRecoveryCandidates(any(), anyInt(), any());
        recovery.reconcilePendingRequests();
        recovery.reconcilePendingRequests();
        verify(transactions, times(1)).renotifyIfEligible(argThat(value -> value.taskId().equals(first.taskId())), any());
        verify(transactions).renotifyIfEligible(argThat(value -> value.taskId().equals(second.taskId())), any());
    }

    @Test
    void 후보_스냅샷은_유실_분류_없이_생성시각을_포함한다() {
        var fixture = fixture(Kind.INITIAL);
        var expected = candidate(fixture);
        var mapper = new ObjectMapper();
        Map<String, Object> value = mapper.readValue(mapper.writeValueAsString(expected),
                new TypeReference<>() {});
        assertThat(value.keySet()).containsExactlyInAnyOrder("taskId", "createdAt", "latestDeliveryNo", "publicationVersion", "publication");
        assertThat(value.get("createdAt")).isNotNull();
    }

    private AttachmentConversionRecoveryService service(int pageSize, int pages, int reissues) {
        return new AttachmentConversionRecoveryService(properties(pageSize, pages, reissues), conversions);
    }

    @Test
    void 후보_조회_뒤_작업이_사라져도_다음_후보의_복구를_계속한다() {
        Fixture removed = fixture(Kind.INITIAL);
        Fixture remaining = fixture(Kind.INITIAL);
        AttachmentConversionRecoveryCandidate removedCandidate = candidate(removed);
        AttachmentConversionRecoveryCandidate remainingCandidate = candidate(remaining);
        doReturn(List.of(removedCandidate, remainingCandidate)).when(transactions)
                .findRecoveryCandidates(any(), eq(10), eq(INTERVAL));
        jdbc.update("DELETE FROM attachment_conversion_outbox WHERE task_id = ?", removed.taskId());
        jdbc.update("DELETE FROM attachment_conversion_tasks WHERE task_id = ?", removed.taskId());

        service(10, 1, 10).reconcilePendingRequests();

        assertThat(tasks.findById(remaining.taskId()).orElseThrow().getLatestDeliveryNo()).isEqualTo(2);
        assertThat(outboxes.findAll()).hasSize(2);
    }

    private AttachmentConversionProperties properties(int pageSize, int pages, int reissues) {
        var admission = new AttachmentConversionProperties.Admission(Map.of(Lane.LIGHT, 100L, Lane.HEIC, 100L),
                Map.of(Lane.LIGHT, 1000L, Lane.HEIC, 1000L), Duration.ofDays(1), Duration.ofSeconds(10), Duration.ofSeconds(1), 0.5);
        return new AttachmentConversionProperties(
                AttachmentConversionRoute.KAFKA,
                admission,
                null,
                null,
                new AttachmentConversionProperties.Recovery(INTERVAL, pageSize, pages, reissues),
                true,
                true,
                true
        );
    }

    private AttachmentConversionRecoveryCandidate candidate(Fixture fixture) {
        return transactions.findRecoveryCandidates(null, 100, INTERVAL).stream()
                .filter(value -> value.taskId().equals(fixture.taskId())).findFirst().orElseThrow();
    }

    private AttachmentConversionRecoveryCandidate snapshot(Fixture fixture) {
        var box = outboxes.findById(fixture.outbox().getId()).orElseThrow();
        var task = tasks.findById(fixture.taskId()).orElseThrow();
        return new AttachmentConversionRecoveryCandidate(task.getId(), task.getCreatedAt(), task.getLatestDeliveryNo(),
                box.getPublicationVersion(), new AttachmentConversionPublication(box.getId(), box.getTopic(),
                box.getTripId().toString(), box.getPayload(), box.getTopicId(), box.getRecordPartition(), box.getRecordOffset(),
                box.getBrokerTimestamp(), box.getLeaderEpoch()));
    }

    private Fixture fixture(Kind kind) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            var user = new User(UUID.randomUUID() + "@yeodam.test", "recovery");
            entityManager.persist(user);
            var trip = new Trip(user.getUserId(), "recovery", LocalDate.now(), LocalDate.now());
            entityManager.persist(trip);
            String executionId = UUID.randomUUID().toString();
            Long batchId;
            if (kind == Kind.INITIAL) {
                entityManager.persist(new TripCreationExecution(executionId, trip.getId(), user.getUserId(), 1));
                var batch = new InitialAttachmentUploadBatch(UUID.randomUUID().toString(), executionId, trip.getId(), user.getUserId(), 1, 1, true);
                batch.accept(NOW, AttachmentConversionRoute.KAFKA);
                entityManager.persist(batch);
                entityManager.persist(new InitialAttachmentUploadItem(batch, 1, "photo.jpg", "image/jpeg", 100L, "original/photo"));
                batchId = batch.getId();
            } else {
                jdbc.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", trip.getId());
                entityManager.persist(new AdditionalAttachmentExecution(executionId, trip.getId(), user.getUserId(), 1, NOW));
                var batch = new AdditionalAttachmentUploadBatch(UUID.randomUUID().toString(), executionId, trip.getId(), user.getUserId(), 1, 1, true);
                batch.markVerified(AttachmentConversionRoute.KAFKA);
                entityManager.persist(batch);
                entityManager.persist(new AdditionalAttachmentUploadItem(batch, 1, "photo.jpg", "image/jpeg", 100L, "original/photo"));
                batchId = batch.getId();
            }
            var task = new AttachmentConversionTask(UUID.randomUUID().toString(), kind, batchId, trip.getId(), Lane.LIGHT, 1, 100);
            entityManager.persist(task);
            var outbox = new AttachmentConversionOutbox(UUID.randomUUID().toString(), task.getId(), 1, trip.getId(),
                    kind == Kind.INITIAL ? "yeodam-image-initial-light" : "yeodam-image-additional-light",
                    "{\"schemaVersion\":1,\"taskId\":\"" + task.getId() + "\"}", "request-recovery");
            outbox.recordPublished(new AttachmentConversionPublication(outbox.getId(), outbox.getTopic(), trip.getId().toString(),
                    outbox.getPayload(), null, 0, 12, Instant.ofEpochMilli(1000), 0), NOW.minus(INTERVAL));
            entityManager.persist(outbox);
            entityManager.flush();
            String itemTable = kind == Kind.INITIAL ? "initial_attachment_upload_items" : "additional_attachment_upload_items";
            jdbc.update("UPDATE " + itemTable + " SET conversion_attempts = 2, derived_json = ?, cleanup_json = ? WHERE upload_batch_id = ?",
                    new ObjectMapper().writeValueAsString(
                            new DerivedPhotoKeys("original/photo", "saved-analyze", "saved-preview")),
                    "[{\"key\":\"old-preview\",\"startedAt\":\"2019-12-01T00:00:00\",\"reason\":\"FAILED_ATTEMPT\",\"deleted\":false}]", batchId);
            return new Fixture(kind, task.getId(), executionId, batchId, outbox);
        });
    }

    private List<Map<String, Object>> protectedState(Fixture fixture) {
        String prefix = fixture.kind() == Kind.INITIAL ? "initial" : "additional";
        return List.of(
                jdbc.queryForMap("SELECT * FROM " + prefix + "_attachment_upload_items WHERE upload_batch_id = ?", fixture.batchId()),
                jdbc.queryForMap("SELECT * FROM " + prefix + "_attachment_upload_batches WHERE upload_batch_id = ?", fixture.batchId()),
                jdbc.queryForMap("SELECT * FROM " + executionTable(fixture.kind()) + " WHERE execution_id = ?", fixture.executionId()));
    }

    private String executionTable(Kind kind) {
        return kind == Kind.INITIAL ? "trip_creation_executions" : "additional_attachment_executions";
    }

    private record Fixture(
            Kind kind,
            String taskId,
            String executionId,
            Long batchId,
            AttachmentConversionOutbox outbox
    ) {
    }
}
