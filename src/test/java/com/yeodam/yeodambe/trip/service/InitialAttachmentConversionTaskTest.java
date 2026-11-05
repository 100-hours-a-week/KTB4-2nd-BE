package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.request.AttachmentConversionSeed;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionAttemptResult;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim.Attempt;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionStepResult;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadBatchSnapshot;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.yeodam.yeodambe.trip.service.response.AttachmentConversionStepResult.Status.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doReturn;

@SpringBootTest(properties = "spring.application.name=initial-conversion-task-test")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InitialAttachmentConversionTaskTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2000, 1, 1, 12, 0);
    @Autowired private InitialAttachmentUploadTransactionService source;
    @Autowired private AttachmentConversionTransactionService conversions;
    @Autowired private AttachmentConversionEnqueueService enqueue;
    @Autowired private AttachmentConversionTaskRepository tasks;
    @Autowired private InitialAttachmentUploadBatchRepository batches;
    @Autowired private InitialAttachmentUploadItemRepository items;
    @Autowired private TripCreationExecutionRepository executions;
    @Autowired private TripCreationOutboxRepository outboxes;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private TripRepository trips;
    @Autowired private UserRepository users;
    @Autowired private UserStatsRepository stats;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private User owner;
    private Trip trip;

    @BeforeEach
    void setUp() {
        doReturn(NOW).when(jdbc).queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
        transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        owner = users.save(new User(UUID.randomUUID() + "@conversion.test", "초기 변환"));
        stats.save(new UserStats(owner));
        trip = trips.save(new Trip(owner.getUserId(), "초기 변환", LocalDate.now(), LocalDate.now()));
    }

    @Test
    void 마지막_사진들이_동시에_완료되어도_photos_ready를_하나만_생성한다() throws Exception {
        var batch = accepted(true);
        var light = claim(batch, Lane.LIGHT);
        var heic = claim(batch, Lane.HEIC);
        var lightAttempt = begin(light);
        var heicAttempt = begin(heic);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var results = List.of(light, heic).stream().map(claim -> workers.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return tx(() -> source.savePhotoSuccess(claim, itemId(claim),
                        output(claim.lane() == Lane.LIGHT ? lightAttempt : heicAttempt)));
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var result : results) {
                assertThat(result.get(15, TimeUnit.SECONDS).status()).isEqualTo(ACK_READY);
            }
        }
        assertThat(aiOutboxes()).hasSize(1);
        assertThat(batches.findById(batch.batchId()).orElseThrow().getStatus()).isEqualTo(InitialAttachmentUploadStatus.READY);
        assertThat(tasks.findById(light.taskId()).orElseThrow().getStatus()).isEqualTo(AttachmentConversionTask.Status.SUCCEEDED);
        assertThat(tasks.findById(heic.taskId()).orElseThrow().getStatus()).isEqualTo(AttachmentConversionTask.Status.SUCCEEDED);
        assertThat(attachments.findAllByTripIdAndDeletedAtIsNull(trip.getId())).hasSize(2);
    }

    @Test
    void 시작_결과가_불확실하면_같은_선점의_동일한_시도를_재사용한다() {
        var claim = claim(accepted(false), Lane.LIGHT);
        Attempt firstAttempt = begin(claim);
        Attempt repeatedAttempt = begin(claim);
        assertThat(repeatedAttempt).isEqualTo(firstAttempt);
        assertThat(repeatedAttempt.plannedKeys()).isEqualTo(firstAttempt.plannedKeys());
        var item = items.findById(itemId(claim)).orElseThrow();
        assertThat(item.getConversionAttempts()).isEqualTo(1);
        assertThat(tx(() -> source.cleanupObjects(items.findById(item.getId()).orElseThrow())))
                .hasSize(3).allSatisfy(object -> assertThat(object.startedAt()).isNull());
    }

    @Test
    void 중복_성공은_사용량을_다시_반영하지_않는다() {
        var claim = claim(accepted(false), Lane.LIGHT);
        var attempt = begin(claim);
        long reserved = stats.findById(owner.getUserId()).orElseThrow().getStorageReservedBytes();
        assertThat(save(claim, attempt).status()).isEqualTo(ACK_READY);
        assertThat(save(claim, attempt).status()).isEqualTo(ACK_READY);
        assertThat(aiOutboxes()).hasSize(1);
        assertThat(attachments.findAllByTripIdAndDeletedAtIsNull(trip.getId())).hasSize(1);
        assertThat(stats.findById(owner.getUserId()).orElseThrow().getStorageReservedBytes()).isEqualTo(reserved);
        assertThat(stats.findById(owner.getUserId()).orElseThrow().getStorageUsedBytes()).isZero();
        assertThat(tx(() -> source.cleanupObjects(items.findById(itemId(claim)).orElseThrow())))
                .allSatisfy(object -> assertThat(object.startedAt()).isNull());
    }

    @Test
    void 이전_선점의_결과는_기록만_하고_적용하지_않는다() {
        var batch = accepted(false);
        var old = claim(batch, Lane.LIGHT);
        var first = begin(old);
        expire(old);
        var current = claim(batch, Lane.LIGHT);
        var second = begin(current);
        assertThat(second.attemptNo()).isEqualTo(2);
        assertThat(second.plannedKeys()).isNotEqualTo(first.plannedKeys());
        assertThat(save(old, first).status()).isEqualTo(RETRY_LATER);
        assertThat(items.findById(itemId(old)).orElseThrow().getTripAttachmentId()).isNull();
        assertThat(attachments.findAllByTripIdAndDeletedAtIsNull(trip.getId())).isEmpty();
        assertThat(tasks.findById(current.taskId()).orElseThrow().getClaimToken()).isEqualTo(current.claimToken());
        assertThat(tx(() -> source.cleanupObjects(items.findById(itemId(old)).orElseThrow())))
                .filteredOn(object -> object.key().equals(first.plannedKeys().analyzeKey()))
                .singleElement().satisfies(object -> assertThat(object.startedAt()).isEqualTo(NOW));
        assertThat(save(current, second).status()).isEqualTo(ACK_READY);
    }

    @Test
    void 재시작해도_재시도_제한을_유지한다() {
        var batch = accepted(false);
        var claim = claim(batch, Lane.LIGHT);
        for (int attemptNo = 1; attemptNo <= 3; attemptNo++) {
            assertThat(begin(claim).attemptNo()).isEqualTo(attemptNo);
            var result = txFailure(claim, false);
            assertThat(result.status()).isEqualTo(attemptNo == 3 ? ACK_READY : RETRY_LATER);
            if (attemptNo < 3) {
                assertThat(result.retryAt()).isEqualTo(NOW.plusSeconds(10L * attemptNo));
                doReturn(result.retryAt()).when(jdbc).queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
                expire(claim);
                claim = claim(batch, Lane.LIGHT);
            }
        }
        assertThat(items.findById(batch.photos().getFirst().itemId()).orElseThrow().getConversionAttempts()).isEqualTo(3);
        assertThat(executions.findById(batch.executionId()).orElseThrow().getErrorCode()).isEqualTo("IMAGE_CONVERSION_FAILED");
        assertThat(tasks.findById(claim.taskId()).orElseThrow().getStatus()).isEqualTo(AttachmentConversionTask.Status.FAILED);
        assertThat(stats.findById(owner.getUserId()).orElseThrow().getStorageReservedBytes()).isZero();
        assertThat(txFailure(claim, false).status()).isEqualTo(ACK_READY);
        assertThat(aiOutboxes()).isEmpty();
        assertThat(tx(() -> source.cleanupObjects(items.findById(batch.photos().getFirst().itemId()).orElseThrow())))
                .allSatisfy(object -> assertThat(object.startedAt()).isNotNull());
    }

    @Test
    void 입력_종료_후_큐_대기는_업로드_시간_초과를_유발하지_않는다() {
        var batch = accepted(false);
        jdbc.update("UPDATE trip_creation_executions SET last_upload_activity_at = ? WHERE execution_id = ?", NOW.minusDays(45), batch.executionId());
        source.failExpiredExecution(batch.executionId(), false);
        source.failExpiredExecution(batch.executionId(), true);
        assertThat(executions.findUploadExpired(NOW, PageRequest.of(0, 100)))
                .noneMatch(execution -> execution.getExecutionId().equals(batch.executionId()));
        assertThat(executions.findById(batch.executionId()).orElseThrow().isTerminal()).isFalse();
        assertThat(begin(claim(batch, Lane.LIGHT)).attemptNo()).isEqualTo(1);
    }

    @Test
    void 이미_저장된_사진은_재변환_없이_부모를_완료한다() {
        var batch = accepted(false);
        jdbc.update("UPDATE initial_attachment_upload_batches SET conversion_route = 'DB' WHERE upload_batch_id = ?", batch.batchId());
        var legacy = source.claimBatch(trip.getId(), owner.getUserId(), batch.uploadId()).orElseThrow();
        var photo = legacy.photos().getFirst();
        var keys = source.startPhotoAttempt(legacy, photo.itemId());
        source.savePhotoSuccess(legacy, photo.itemId(), new DerivedPhotoKeys(photo.objectKey(), keys.get(0), keys.get(1),
                null, null, null, null, null, 100L, 50L, 25L, null));
        jdbc.update("UPDATE initial_attachment_upload_batches SET conversion_route = 'KAFKA', claim_token = NULL, lease_until = NULL WHERE upload_batch_id = ?", batch.batchId());
        var claim = claim(batch, Lane.LIGHT);
        assertThat(claim.photos().getFirst().derived()).isNotNull();
        var begin = tx(() -> source.startPhotoAttempt(claim, itemId(claim)));
        assertThat(begin.status()).isEqualTo(AttachmentConversionAttemptResult.Status.ALREADY_SAVED);
        assertThat(begin.attempt()).isNull();
        assertThat(tx(() -> source.completeConvertedBatch(claim)).status()).isEqualTo(ACK_READY);
        assertThat(tx(() -> source.completeConvertedBatch(claim)).status()).isEqualTo(ACK_READY);
        assertThat(items.findById(photo.itemId()).orElseThrow().getConversionAttempts()).isEqualTo(1);
        assertThat(aiOutboxes()).hasSize(1);
    }

    @Test
    void 사진과_작업과_부모_완료_사이에서_롤백해도_부분_상태를_남기지_않는다() {
        var claim = claim(accepted(false), Lane.LIGHT);
        var attempt = begin(claim);
        transaction.executeWithoutResult(status -> {
            assertThat(source.savePhotoSuccess(claim, itemId(claim), output(attempt)).status()).isEqualTo(ACK_READY);
            assertThat(aiOutboxes()).hasSize(1);
            status.setRollbackOnly();
        });
        assertThat(items.findById(itemId(claim)).orElseThrow().getDerivedJson()).isNull();
        assertThat(tasks.findById(claim.taskId()).orElseThrow().getStatus()).isEqualTo(AttachmentConversionTask.Status.RUNNING);
        assertThat(aiOutboxes()).isEmpty();
        assertThat(attachments.findAllByTripIdAndDeletedAtIsNull(trip.getId())).isEmpty();
        assertThat(save(claim, attempt).status()).isEqualTo(ACK_READY);
        assertThat(aiOutboxes()).hasSize(1);
    }

    @Test
    void 시작을_롤백하면_시도_횟수를_소모하거나_예정_키를_남기지_않는다() {
        var claim = claim(accepted(false), Lane.LIGHT);
        transaction.executeWithoutResult(status -> {
            assertThat(source.startPhotoAttempt(claim, itemId(claim)).attempt().attemptNo()).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(items.findById(itemId(claim)).orElseThrow().getConversionAttempts()).isZero();
        assertThat(tasks.findById(claim.taskId()).orElseThrow().getCurrentItemId()).isNull();
        assertThat(begin(claim).attemptNo()).isEqualTo(1);
    }

    @Test
    void 영구_실패는_예약량을_한_번_해제하고_객체를_30일_보존한다() {
        var claim = claim(accepted(false), Lane.LIGHT);
        begin(claim);
        assertThat(txFailure(claim, true).status()).isEqualTo(ACK_READY);
        assertThat(txFailure(claim, true).status()).isEqualTo(ACK_READY);
        assertThat(stats.findById(owner.getUserId()).orElseThrow().getStorageReservedBytes()).isZero();
        assertThat(items.findPendingCleanup(NOW.plusDays(30).minusDays(30), PageRequest.of(0, 100)))
                .anyMatch(item -> item.getId().equals(itemId(claim)));
        assertThat(items.findPendingCleanup(NOW.minusNanos(1000), PageRequest.of(0, 100)))
                .noneMatch(item -> item.getId().equals(itemId(claim)));
        assertThat(tx(() -> source.cleanupObjects(items.findById(itemId(claim)).orElseThrow())))
                .allSatisfy(object -> {
                    assertThat(object.startedAt()).isEqualTo(NOW);
                    assertThat(object.deleted()).isFalse();
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 취소하거나_삭제하면_늦은_결과를_활성화하지_않는다(boolean deleted) {
        var claim = claim(accepted(false), Lane.LIGHT);
        var attempt = begin(claim);
        if (deleted) {
            jdbc.update("UPDATE trips SET deleted_at = ? WHERE trip_id = ?", NOW, trip.getId());
        } else {
            source.cancelExecution(trip.getId(), owner.getUserId());
        }
        assertThat(save(claim, attempt).status()).isEqualTo(ACK_READY);
        assertThat(items.findById(itemId(claim)).orElseThrow().getDerivedJson()).isNull();
        assertThat(aiOutboxes()).isEmpty();
        assertThat(tasks.findById(claim.taskId()).orElseThrow().getStatus()).isEqualTo(AttachmentConversionTask.Status.CANCELED);
        assertThat(tx(() -> source.cleanupObjects(items.findById(itemId(claim)).orElseThrow())))
                .allSatisfy(object -> assertThat(object.startedAt()).isNotNull());
    }

    @Test
    void 잘못된_항목과_키로_다른_레인에_쓸_수_없다() {
        var batch = accepted(true);
        var light = claim(batch, Lane.LIGHT);
        var attempt = begin(light);
        Long heicId = batch.photos().getLast().itemId();
        assertThatThrownBy(() -> tx(() -> source.startPhotoAttempt(light, heicId)))
                .isInstanceOf(com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException.class);
        var bad = new DerivedPhotoKeys(attempt.plannedKeys().originalKey(), "wrong", "wrong", null,
                null, null, null, null, 100L, 50L, 25L, null);
        assertThatThrownBy(() -> tx(() -> source.savePhotoSuccess(light, itemId(light), bad)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(items.findById(heicId).orElseThrow().getConversionAttempts()).isZero();
        assertThat(aiOutboxes()).isEmpty();
    }

    @Test
    void 모든_오버로드는_호출자의_트랜잭션을_요구한다() {
        var claim = claim(accepted(false), Lane.LIGHT);
        assertThatThrownBy(() -> source.startPhotoAttempt(claim, itemId(claim)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> source.savePhotoSuccess(claim, itemId(claim), null))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> source.recordPhotoFailure(claim, itemId(claim), false))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> source.completeConvertedBatch(claim))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void 재시도_시각과_저장된_사진은_서로_다른_시작_결과를_반환한다() {
        var batch = accepted(false);
        var claim = claim(batch, Lane.LIGHT);
        begin(claim);
        var retry = txFailure(claim, false);
        var waiting = tx(() -> source.startPhotoAttempt(claim, itemId(claim)));
        assertThat(waiting.status()).isEqualTo(AttachmentConversionAttemptResult.Status.RETRY_LATER);
        assertThat(waiting.retryAt()).isEqualTo(retry.retryAt());
        assertThat(waiting.attempt()).isNull();
        expire(claim);
        doReturn(retry.retryAt()).when(jdbc).queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
        var resumed = claim(batch, Lane.LIGHT);
        assertThat(resumed.requestId()).isEqualTo("initial-task-request");
        assertThat(resumed.photos().getFirst().attempts()).isEqualTo(1);
        assertThat(resumed.photos().getFirst().nextAttemptAt()).isEqualTo(retry.retryAt());
        assertThat(begin(resumed).attemptNo()).isEqualTo(2);
    }

    @Test
    void 세_번째_시작_후_재시작하면_네_번째_변환_없이_실패한다() {
        var batch = accepted(false);
        var claim = claim(batch, Lane.LIGHT);
        for (int number = 1; number <= 3; number++) {
            assertThat(begin(claim).attemptNo()).isEqualTo(number);
            expire(claim);
            claim = claim(batch, Lane.LIGHT);
        }
        var exhausted = claim;
        var result = tx(() -> source.startPhotoAttempt(exhausted, itemId(exhausted)));
        assertThat(result.status()).isEqualTo(AttachmentConversionAttemptResult.Status.ACK_READY);
        assertThat(result.attempt()).isNull();
        assertThat(items.findById(itemId(exhausted)).orElseThrow().getConversionAttempts()).isEqualTo(3);
        assertThat(executions.findById(batch.executionId()).orElseThrow().getErrorCode()).isEqualTo("IMAGE_CONVERSION_FAILED");
        assertThat(stats.findById(owner.getUserId()).orElseThrow().getStorageReservedBytes()).isZero();
    }

    @Test
    void 다른_선점_전에도_만료된_소유자의_결과를_추적한다() {
        var claim = claim(accepted(false), Lane.LIGHT);
        var attempt = begin(claim);
        expire(claim);
        assertThat(save(claim, attempt).status()).isEqualTo(RETRY_LATER);
        assertThat(tx(() -> source.cleanupObjects(items.findById(itemId(claim)).orElseThrow())))
                .filteredOn(object -> !object.key().equals(claim.photos().getFirst().objectKey()))
                .allSatisfy(object -> assertThat(object.startedAt()).isEqualTo(NOW));
        assertThat(items.findById(itemId(claim)).orElseThrow().getDerivedJson()).isNull();
    }

    @Test
    void 늦은_이전_결과는_활성_성공_키를_정리_예약하거나_보존_시각을_초기화하지_않는다() {
        var batch = accepted(false);
        var old = claim(batch, Lane.LIGHT);
        var first = begin(old);
        expire(old);
        var current = claim(batch, Lane.LIGHT);
        var second = begin(current);
        save(current, second);
        doReturn(NOW.plusDays(2)).when(jdbc).queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
        assertThat(save(old, first).status()).isEqualTo(ACK_READY);
        assertThat(save(current, second).status()).isEqualTo(ACK_READY);
        var journal = tx(() -> source.cleanupObjects(items.findById(itemId(old)).orElseThrow()));
        assertThat(journal).filteredOn(object -> object.key().equals(first.plannedKeys().analyzeKey()))
                .singleElement().satisfies(object -> assertThat(object.startedAt()).isEqualTo(NOW));
        assertThat(journal).filteredOn(object -> object.key().equals(second.plannedKeys().analyzeKey()))
                .singleElement().satisfies(object -> assertThat(object.startedAt()).isNull());
        assertThat(attachments.findAllByTripIdAndDeletedAtIsNull(trip.getId())).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 취소와_사진_저장은_여행_잠금으로_직렬화한다(boolean cancellationWins) throws Exception {
        var claim = claim(accepted(false), Lane.LIGHT);
        var attempt = begin(claim);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var contenderStarted = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> transaction.executeWithoutResult(status -> {
                trips.findOwnedActiveForUpdate(trip.getId(), owner.getUserId()).orElseThrow();
                if (cancellationWins) {
                    source.cancelExecution(trip.getId(), owner.getUserId());
                } else {
                    source.savePhotoSuccess(claim, itemId(claim), output(attempt));
                }
                locked.countDown();
                try {
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(() -> {
                contenderStarted.countDown();
                if (cancellationWins) {
                    save(claim, attempt);
                } else {
                    transaction.executeWithoutResult(status -> {
                        trips.findOwnedActiveForUpdate(trip.getId(), owner.getUserId()).orElseThrow();
                        source.cancelExecution(trip.getId(), owner.getUserId());
                    });
                }
            });
            try {
                assertThat(contenderStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                release.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(executions.findById(claim.executionId()).orElseThrow().getStatus()).isEqualTo(TripCreationExecution.Status.CANCELED);
        assertThat(attachments.findAllByTripIdAndDeletedAtIsNull(trip.getId())).isEmpty();
        assertThat(stats.findById(owner.getUserId()).orElseThrow().getStorageReservedBytes()).isZero();
        assertThat(aiOutboxes()).hasSize(cancellationWins ? 0 : 1);
        assertThat(tx(() -> source.cleanupObjects(items.findById(itemId(claim)).orElseThrow())))
                .allSatisfy(object -> assertThat(object.startedAt()).isNotNull());
    }

    @Test
    void 부분_완료와_레인_완료는_잔여량을_한_번_갱신하고_다른_형식을_기다린다() {
        var batch = accepted(List.of(
                new InitialAttachmentUploadUrlRequest.Attachment("first.jpg", "image/jpeg", 100L),
                new InitialAttachmentUploadUrlRequest.Attachment("second.png", "image/png", 110L),
                new InitialAttachmentUploadUrlRequest.Attachment("last.heic", "image/heic", 200L)));
        var light = claim(batch, Lane.LIGHT);
        var first = begin(light);
        assertThat(save(light, first).status()).isEqualTo(CONTINUE);
        assertThat(save(light, first).status()).isEqualTo(CONTINUE);
        var remaining = tasks.findById(light.taskId()).orElseThrow();
        assertThat(remaining.getRemainingPhotos()).isEqualTo(1);
        assertThat(remaining.getRemainingBytes()).isEqualTo(110);
        Long secondId = light.photos().getLast().itemId();
        var second = tx(() -> source.startPhotoAttempt(light, secondId)).attempt();
        assertThat(tx(() -> source.savePhotoSuccess(light, secondId, output(second))).status()).isEqualTo(ACK_READY);
        assertThat(tasks.findById(light.taskId()).orElseThrow().getRemainingPhotos()).isZero();
        assertThat(aiOutboxes()).isEmpty();
        assertThat(batches.findById(batch.batchId()).orElseThrow().getOutboxId()).isNull();
        var heic = claim(batch, Lane.HEIC);
        assertThat(save(heic, begin(heic)).status()).isEqualTo(ACK_READY);
        assertThat(aiOutboxes()).hasSize(1);
        assertThat(attachments.findAllByTripIdAndDeletedAtIsNull(trip.getId())).hasSize(3);
    }

    @Test
    void 종료된_작업에_다른_활성_소유자가_있으면_정리를_기다린다() {
        var claim = claim(accepted(false), Lane.LIGHT);
        var attempt = begin(claim);
        jdbc.update("UPDATE attachment_conversion_tasks SET status = 'SUCCEEDED', claim_token = 'other', lease_until = ? WHERE task_id = ?",
                NOW.plusMinutes(10), claim.taskId());
        var completed = tx(() -> source.completeConvertedBatch(claim));
        assertThat(completed.status()).isEqualTo(RETRY_LATER);
        assertThat(completed.retryAt()).isEqualTo(NOW.plusMinutes(10));
        assertThat(tx(() -> source.startPhotoAttempt(claim, itemId(claim))).status())
                .isEqualTo(AttachmentConversionAttemptResult.Status.RETRY_LATER);
        assertThat(txFailure(claim, true).status()).isEqualTo(RETRY_LATER);
        assertThat(save(claim, attempt).status()).isEqualTo(RETRY_LATER);
        assertThat(items.findById(itemId(claim)).orElseThrow().getTripAttachmentId()).isNull();
        assertThat(aiOutboxes()).isEmpty();
    }

    private AttachmentConversionStepResult txFailure(AttachmentConversionClaim claim, boolean permanent) {
        return tx(() -> source.recordPhotoFailure(claim, itemId(claim), permanent));
    }

    private InitialAttachmentUploadBatchSnapshot accepted(boolean mixed) {
        var photos = mixed ? List.of(new InitialAttachmentUploadUrlRequest.Attachment("light.jpg", "image/jpeg", 100L),
                new InitialAttachmentUploadUrlRequest.Attachment("heic.heic", "image/heic", 200L))
                : List.of(new InitialAttachmentUploadUrlRequest.Attachment("light.jpg", "image/jpeg", 100L));
        return accepted(photos);
    }

    private InitialAttachmentUploadBatchSnapshot accepted(List<InitialAttachmentUploadUrlRequest.Attachment> photos) {
        var prepared = source.prepareBatch(trip.getId(), owner.getUserId(),
                new InitialAttachmentUploadUrlRequest(1, photos.size(), true, photos));
        transaction.executeWithoutResult(status -> {
            batches.findById(prepared.batchId()).orElseThrow().accept(NOW, AttachmentConversionRoute.KAFKA);
            executions.findById(prepared.executionId()).orElseThrow().closeInput();
            enqueue.enqueue(new AttachmentConversionSeed(Kind.INITIAL, prepared.batchId(), trip.getId(), "initial-task-request",
                    prepared.photos().stream().map(photo -> new AttachmentConversionSeed.Photo(
                            photo.itemId(), photo.contentType(), photo.sizeBytes())).toList()));
        });
        return prepared;
    }

    private AttachmentConversionClaim claim(InitialAttachmentUploadBatchSnapshot batch, Lane lane) {
        String taskId = tasks.findByInitialBatchIdAndLane(batch.batchId(), lane).orElseThrow().getId();
        return conversions.claim(taskId, Kind.INITIAL, lane).claim();
    }

    private Attempt begin(AttachmentConversionClaim claim) {
        var result = tx(() -> source.startPhotoAttempt(claim, itemId(claim)));
        assertThat(result.status()).isEqualTo(AttachmentConversionAttemptResult.Status.STARTED);
        return result.attempt();
    }

    private Long itemId(AttachmentConversionClaim claim) {
        return claim.photos().getFirst().itemId();
    }

    private AttachmentConversionStepResult save(AttachmentConversionClaim claim, Attempt attempt) {
        return tx(() -> source.savePhotoSuccess(claim, itemId(claim), output(attempt)));
    }

    private DerivedPhotoKeys output(Attempt attempt) {
        var keys = attempt.plannedKeys();
        return new DerivedPhotoKeys(keys.originalKey(), keys.analyzeKey(), keys.previewKey(), keys.displayKey(),
                null, null, null, null, keys.originalSizeBytes(), 50L, 25L, keys.displayKey() == null ? null : 75L);
    }

    private void expire(AttachmentConversionClaim claim) {
        jdbc.update("UPDATE attachment_conversion_tasks SET lease_until = ? WHERE task_id = ?", NOW.minusSeconds(1), claim.taskId());
    }

    private List<TripCreationOutbox> aiOutboxes() {
        return outboxes.findAll().stream().filter(outbox -> outbox.getTripId().equals(trip.getId())).toList();
    }

    private <T> T tx(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }
}
