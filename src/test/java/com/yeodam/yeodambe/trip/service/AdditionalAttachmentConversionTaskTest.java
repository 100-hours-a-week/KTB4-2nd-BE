package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.request.AttachmentConversionSeed;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadCompleteRequest;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionAttemptResult;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim.Attempt;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionStepResult;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

import static com.yeodam.yeodambe.trip.service.response.AttachmentConversionAttemptResult.Status.STARTED;
import static com.yeodam.yeodambe.trip.service.response.AttachmentConversionStepResult.Status.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.application.name=additional-conversion-task-test")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AdditionalAttachmentConversionTaskTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2000, 1, 1, 12, 0);
    @MockitoSpyBean private AdditionalAttachmentUploadTransactionService source;
    @Autowired private InitialAttachmentUploadTransactionService initial;
    @Autowired private AdditionalAttachmentUploadCompletionService completion;
    @Autowired private AttachmentConversionTransactionService conversions;
    @Autowired private AttachmentConversionEnqueueService enqueue;
    @Autowired private AttachmentConversionTaskRepository tasks;
    @Autowired private AdditionalAttachmentUploadBatchRepository batches;
    @Autowired private AdditionalAttachmentUploadItemRepository items;
    @Autowired private AdditionalAttachmentExecutionRepository executions;
    @Autowired private AdditionalAttachmentUploadOutboxRepository outboxes;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private TripRepository trips;
    @Autowired private UserRepository users;
    @Autowired private UserStatsRepository stats;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private JdbcTemplate jdbc;
    @MockitoBean private TripAttachmentStorageClient storage;
    @MockitoBean private TripAttachmentDerivativeService derivatives;
    private TransactionTemplate transaction;
    private User owner;
    private Trip trip;

    @BeforeEach
    void setUp() {
        doReturn(NOW).when(jdbc).queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
        transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        owner = users.save(new User(UUID.randomUUID() + "@conversion.test", "추가 변환"));
        stats.save(new UserStats(owner));
        trip = trips.save(new Trip(owner.getUserId(), "추가 변환", LocalDate.now(), LocalDate.now()));
        jdbc.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", trip.getId());
    }

    @Test
    void 두_형식의_변환이_추가_배치를_한_번만_완료한다() throws Exception {
        var batch = accepted(true);
        var light = claim(batch, Lane.LIGHT);
        var heic = claim(batch, Lane.HEIC);
        var first = begin(light);
        var second = begin(heic);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var results = List.of(light, heic).stream().map(claim -> workers.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return conversions.completePhoto(claim, itemId(claim), output(claim.lane() == Lane.LIGHT ? first : second));
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var result : results) {
                assertThat(result.get(15, TimeUnit.SECONDS).status()).isEqualTo(ACK_READY);
            }
        }
        assertThat(readyOutboxes(batch)).hasSize(1);
        assertThat(batches.findById(batch.getId()).orElseThrow().getStatus()).isEqualTo(AdditionalAttachmentUploadStatus.PREPARED);
        assertThat(tasks.findById(light.taskId()).orElseThrow().getStatus()).isEqualTo(AttachmentConversionTask.Status.SUCCEEDED);
        assertThat(tasks.findById(heic.taskId()).orElseThrow().getStatus()).isEqualTo(AttachmentConversionTask.Status.SUCCEEDED);
        assertThat(attachments.findAllByTripIdAndDeletedAtIsNull(trip.getId())).hasSize(2);
        assertThat(executions.findById(batch.getAdditionId()).orElseThrow().getAccountedBytes()).isEqualTo(300);
        assertUsage(0, 300);
    }

    @Test
    void 중복_성공은_예약량을_한_번만_해제한다() {
        var batch = accepted(false);
        var claim = claim(batch, Lane.LIGHT);
        var attempt = begin(claim);
        assertThat(save(claim, attempt).status()).isEqualTo(ACK_READY);
        String firstDerivedJson = items.findById(itemId(claim)).orElseThrow().getDerivedJson();
        assertThat(save(claim, attempt).status()).isEqualTo(ACK_READY);
        assertThat(items.findById(itemId(claim)).orElseThrow().getDerivedJson()).isEqualTo(firstDerivedJson);
        assertThat(readyOutboxes(batch)).hasSize(1);
        assertThat(executions.findById(batch.getAdditionId()).orElseThrow().getAccountedBytes()).isEqualTo(100);
        assertThat(attachments.findAllByTripIdAndDeletedAtIsNull(trip.getId())).hasSize(1);
        assertUsage(0, 100);
    }

    @Test
    void 다른_형식_성공_후_실패해도_30일_정리_보존을_유지한다() {
        var batch = accepted(true);
        var light = claim(batch, Lane.LIGHT);
        assertThat(save(light, begin(light)).status()).isEqualTo(ACK_READY);
        assertUsage(200, 100);
        assertThat(readyOutboxes(batch)).isEmpty();
        var heic = claim(batch, Lane.HEIC);
        begin(heic);
        assertThat(conversions.recordPhotoFailure(heic, itemId(heic), true).status()).isEqualTo(ACK_READY);
        assertThat(conversions.recordPhotoFailure(heic, itemId(heic), true).status()).isEqualTo(ACK_READY);
        assertUsage(0, 0);
        assertThat(attachments.findAllByTripIdAndDeletedAtIsNull(trip.getId())).isEmpty();
        assertThat(executions.findById(batch.getAdditionId()).orElseThrow().getStatus()).isEqualTo(AdditionalAttachmentExecution.Status.FAILED);
        assertThat(readyOutboxes(batch)).isEmpty();
        for (var item : items.findAdditionItems(batch.getAdditionId())) {
            assertThat(journal(item.getId())).allSatisfy(object -> {
                assertThat(object.startedAt()).isEqualTo(NOW);
                assertThat(object.deleted()).isFalse();
            });
        }
        assertThat(items.findPendingCleanup(NOW.minusNanos(1000), org.springframework.data.domain.PageRequest.of(0, 100)))
                .noneMatch(item -> item.getBatch().getId().equals(batch.getId()));
        assertThat(items.findPendingCleanup(NOW.plusDays(30).minusDays(30), org.springframework.data.domain.PageRequest.of(0, 100)))
                .anyMatch(item -> item.getId().equals(itemId(heic)));
    }

    @Test
    void 만료된_토큰으로_첨부를_덮어쓸_수_없다() {
        var batch = accepted(false);
        var old = claim(batch, Lane.LIGHT);
        var first = begin(old);
        expire(old);
        var current = claim(batch, Lane.LIGHT);
        var second = begin(current);
        assertThat(save(old, first).status()).isEqualTo(RETRY_LATER);
        assertThat(items.findById(itemId(old)).orElseThrow().getDerivedJson()).isNull();
        assertThat(journal(itemId(old))).filteredOn(object -> object.key().equals(second.plannedKeys().analyzeKey()))
                .singleElement().satisfies(object -> assertThat(object.startedAt()).isNull());
        assertUsage(100, 0);
        assertThat(save(current, second).status()).isEqualTo(ACK_READY);
        String firstDerivedJson = items.findById(itemId(current)).orElseThrow().getDerivedJson();
        doReturn(NOW.plusDays(2)).when(jdbc).queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
        save(old, first);
        save(current, second);
        assertThat(items.findById(itemId(current)).orElseThrow().getDerivedJson()).isEqualTo(firstDerivedJson);
        assertThat(journal(itemId(old))).filteredOn(object -> object.key().equals(first.plannedKeys().analyzeKey()))
                .singleElement().satisfies(object -> assertThat(object.startedAt()).isEqualTo(NOW));
        assertThat(journal(itemId(old))).filteredOn(object -> object.key().equals(second.plannedKeys().analyzeKey()))
                .singleElement().satisfies(object -> assertThat(object.startedAt()).isNull());
        assertUsage(0, 100);
    }

    @Test
    void 현재_시도의_DB_재시도는_시도_횟수를_다시_늘리지_않는다() {
        var claim = claim(accepted(false), Lane.LIGHT);
        var first = begin(claim);
        assertThat(begin(claim)).isEqualTo(first);
        assertThat(first.plannedKeys().analyzeKey()).endsWith("/1/analyze.jpg");
        assertThat(items.findById(itemId(claim)).orElseThrow().getConversionAttempts()).isEqualTo(1);
        assertThat(journal(itemId(claim))).hasSize(3);
    }

    @Test
    void 추가_변환의_재시도_한도는_재시작_후에도_유지된다() {
        var batch = accepted(false);
        var current = claim(batch, Lane.LIGHT);
        for (int number = 1; number <= 3; number++) {
            assertThat(begin(current).attemptNo()).isEqualTo(number);
            var result = conversions.recordPhotoFailure(current, itemId(current), false);
            assertThat(result.status()).isEqualTo(number == 3 ? ACK_READY : RETRY_LATER);
            if (number < 3) {
                assertThat(result.retryAt()).isEqualTo(NOW.plusSeconds(10L * number));
                assertThat(conversions.beginPhoto(current, itemId(current)).status())
                        .isEqualTo(AttachmentConversionAttemptResult.Status.RETRY_LATER);
                expire(current);
                doReturn(result.retryAt()).when(jdbc).queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
                current = claim(batch, Lane.LIGHT);
            }
        }
        assertThat(items.findById(itemId(current)).orElseThrow().getConversionAttempts()).isEqualTo(3);
        assertThat(tasks.findById(current.taskId()).orElseThrow().getStatus()).isEqualTo(AttachmentConversionTask.Status.FAILED);
        assertUsage(0, 0);
    }

    @Test
    void 세_번째_시작_중_장애시_네_번째_시도_없이_ACK_전에_실패를_커밋한다() {
        var batch = accepted(false);
        var claim = claim(batch, Lane.LIGHT);
        for (int number = 1; number <= 3; number++) {
            assertThat(begin(claim).attemptNo()).isEqualTo(number);
            expire(claim);
            claim = claim(batch, Lane.LIGHT);
        }
        var result = conversions.beginPhoto(claim, itemId(claim));
        assertThat(result.status()).isEqualTo(AttachmentConversionAttemptResult.Status.ACK_READY);
        assertThat(result.attempt()).isNull();
        assertThat(items.findById(itemId(claim)).orElseThrow().getConversionAttempts()).isEqualTo(3);
        assertThat(tasks.findById(claim.taskId()).orElseThrow().getStatus()).isEqualTo(AttachmentConversionTask.Status.FAILED);
        assertThat(executions.findById(batch.getAdditionId()).orElseThrow().getErrorCode()).isEqualTo("IMAGE_CONVERSION_FAILED");
        assertUsage(0, 0);
    }

    @Test
    void 이미_저장된_사진은_중복_정산_없이_상위_작업을_완료한다() {
        var batch = accepted(false);
        jdbc.update("UPDATE additional_attachment_upload_batches SET conversion_route = 'DB' WHERE upload_batch_id = ?", batch.getId());
        jdbc.update("UPDATE additional_attachment_upload_batches SET next_attempt_at = '2099-01-01' WHERE upload_batch_id <> ?", batch.getId());
        var legacy = source.claimBatch().orElseThrow();
        Long id = legacy.photos().getFirst().itemId();
        var keys = source.startPhotoAttempt(legacy, id);
        source.savePhotoSuccess(legacy, id, new DerivedPhotoKeys(legacy.photos().getFirst().objectKey(), keys.get(0), keys.get(1), null,
                null, null, null, null, 100L, 50L, 25L, null));
        jdbc.update("UPDATE additional_attachment_upload_batches SET conversion_route = 'KAFKA', worker_token = NULL, lease_expires_at = NULL, status = 'VERIFIED' WHERE upload_batch_id = ?", batch.getId());
        var claim = claim(batch, Lane.LIGHT);
        var result = conversions.beginPhoto(claim, id);
        assertThat(result.status()).isEqualTo(AttachmentConversionAttemptResult.Status.ALREADY_SAVED);
        assertThat(result.attempt()).isNull();
        assertThat(conversions.finishSubBatch(claim).status()).isEqualTo(ACK_READY);
        assertThat(conversions.finishSubBatch(claim).status()).isEqualTo(ACK_READY);
        assertThat(readyOutboxes(batch)).hasSize(1);
        assertThat(items.findById(id).orElseThrow().getConversionAttempts()).isEqualTo(1);
        assertUsage(0, 100);
    }

    @Test
    void 롤백시_사진과_정산과_작업과_Outbox를_부분_저장하지_않는다() {
        var batch = accepted(false);
        var claim = claim(batch, Lane.LIGHT);
        transaction.executeWithoutResult(status -> {
            source.startPhotoAttempt(claim, itemId(claim));
            status.setRollbackOnly();
        });
        assertThat(items.findById(itemId(claim)).orElseThrow().getConversionAttempts()).isZero();
        var attempt = begin(claim);
        transaction.executeWithoutResult(status -> {
            assertThat(source.savePhotoSuccess(claim, itemId(claim), output(attempt)).status()).isEqualTo(ACK_READY);
            status.setRollbackOnly();
        });
        assertUsage(100, 0);
        assertThat(items.findById(itemId(claim)).orElseThrow().getTripAttachmentId()).isNull();
        assertThat(tasks.findById(claim.taskId()).orElseThrow().getStatus()).isEqualTo(AttachmentConversionTask.Status.RUNNING);
        assertThat(readyOutboxes(batch)).isEmpty();
        assertThat(attachments.findAllByTripIdAndDeletedAtIsNull(trip.getId())).isEmpty();
        assertThat(save(claim, attempt).status()).isEqualTo(ACK_READY);
        assertUsage(0, 100);
    }

    @Test
    void 잘못된_소속과_만료된_제어권으로_적용하거나_ACK할_수_없다() {
        var batch = accepted(true);
        var claim = claim(batch, Lane.LIGHT);
        var attempt = begin(claim);
        Long otherId = items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId()).getLast().getId();
        assertThatThrownBy(() -> conversions.beginPhoto(claim, otherId)).isInstanceOf(InvalidAttachmentUploadException.class);
        var invalid = new DerivedPhotoKeys(attempt.plannedKeys().originalKey(), "wrong", "wrong", null,
                null, null, null, null, 100L, 50L, 25L, null);
        assertThatThrownBy(() -> conversions.completePhoto(claim, itemId(claim), invalid)).isInstanceOf(IllegalStateException.class);
        expire(claim);
        assertThat(save(claim, attempt).status()).isEqualTo(RETRY_LATER);
        assertThat(conversions.finishSubBatch(claim).status()).isEqualTo(RETRY_LATER);
        assertThat(journal(itemId(claim))).filteredOn(object -> !object.key().equals(claim.photos().getFirst().objectKey()))
                .allSatisfy(object -> assertThat(object.startedAt()).isEqualTo(NOW));
        assertUsage(300, 0);
    }

    @Test
    void 다른_유효_소유자가_있으면_종료_ACK를_거부한다() {
        var claim = claim(accepted(false), Lane.LIGHT);
        var attempt = begin(claim);
        jdbc.update("UPDATE attachment_conversion_tasks SET status = 'SUCCEEDED', claim_token = 'other', lease_until = ? WHERE task_id = ?",
                NOW.plusMinutes(10), claim.taskId());
        assertThat(conversions.beginPhoto(claim, itemId(claim)).status()).isEqualTo(AttachmentConversionAttemptResult.Status.RETRY_LATER);
        assertThat(conversions.finishSubBatch(claim).status()).isEqualTo(RETRY_LATER);
        assertThat(conversions.recordPhotoFailure(claim, itemId(claim), true).status()).isEqualTo(RETRY_LATER);
        assertThat(save(claim, attempt).status()).isEqualTo(RETRY_LATER);
        assertUsage(100, 0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 삭제_확인과_실패가_동시에_발생해도_두_저널_갱신을_보존한다(boolean deletionFirst) throws Exception {
        var batch = accepted(false);
        var claim = claim(batch, Lane.LIGHT);
        var attempt = begin(claim);
        String key = attempt.plannedKeys().analyzeKey();
        var retry = conversions.recordPhotoFailure(claim, itemId(claim), false);
        doReturn(retry.retryAt()).when(jdbc).queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
        begin(claim);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var contender = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> transaction.executeWithoutResult(status -> {
                if (deletionFirst) {
                    source.recordObjectDeleted(itemId(claim), key);
                } else {
                    source.recordPhotoFailure(claim, itemId(claim), true);
                }
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(() -> {
                contender.countDown();
                if (deletionFirst) {
                    conversions.recordPhotoFailure(claim, itemId(claim), true);
                } else {
                    source.recordObjectDeleted(itemId(claim), key);
                }
            });
            try {
                assertThat(contender.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                release.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(journal(itemId(claim))).filteredOn(object -> object.key().equals(key)).singleElement()
                .satisfies(object -> {
                    assertThat(object.deleted()).isTrue();
                    assertThat(object.startedAt()).isEqualTo(NOW);
                });
        assertThat(journal(itemId(claim))).allSatisfy(object -> assertThat(object.startedAt()).isNotNull());
        assertUsage(0, 0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 탈퇴나_삭제_후_늦은_성공을_차단하고_정리를_유지한다(boolean withdrawal) {
        var batch = accepted(false);
        var claim = claim(batch, Lane.LIGHT);
        var attempt = begin(claim);
        transaction.executeWithoutResult(status -> {
            trips.findOwnedActiveForUpdate(trip.getId(), owner.getUserId()).orElseThrow();
            if (withdrawal) {
                source.cancelForWithdrawal(trip.getId(), owner.getUserId());
            }
            jdbc.update("UPDATE trips SET deleted_at = ? WHERE trip_id = ?", NOW, trip.getId());
        });
        assertThat(save(claim, attempt).status()).isEqualTo(ACK_READY);
        assertThat(items.findById(itemId(claim)).orElseThrow().getDerivedJson()).isNull();
        assertThat(journal(itemId(claim))).allSatisfy(object -> assertThat(object.startedAt()).isNotNull());
        assertUsage(0, 0);
    }

    @Test
    void 소스_오버로드는_호출자의_트랜잭션을_요구한다() {
        var claim = claim(accepted(false), Lane.LIGHT);
        assertThatThrownBy(() -> source.startPhotoAttempt(claim, itemId(claim)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> source.savePhotoSuccess(claim, itemId(claim), null))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> source.recordPhotoFailure(claim, itemId(claim), true))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> source.completeConvertedBatch(claim))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void 파사드는_초기_소스도_동일한_결과로_커밋한다() {
        var initialTrip = trips.save(new Trip(owner.getUserId(), "초기", LocalDate.now(), LocalDate.now()));
        var batch = initial.prepareBatch(initialTrip.getId(), owner.getUserId(), new InitialAttachmentUploadUrlRequest(
                1, 1, true, List.of(new InitialAttachmentUploadUrlRequest.Attachment("one.jpg", "image/jpeg", 100L))));
        transaction.executeWithoutResult(status -> {
            entityManager.find(InitialAttachmentUploadBatch.class, batch.batchId()).accept(NOW, AttachmentConversionRoute.KAFKA);
            enqueue.enqueue(new AttachmentConversionSeed(Kind.INITIAL, batch.batchId(), initialTrip.getId(), "initial",
                    List.of(new AttachmentConversionSeed.Photo(batch.photos().getFirst().itemId(), "image/jpeg", 100L))));
        });
        var task = tasks.findByInitialBatchIdAndLane(batch.batchId(), Lane.LIGHT).orElseThrow();
        var claim = conversions.claim(task.getId(), Kind.INITIAL, Lane.LIGHT).claim();
        var attempt = begin(claim);
        assertThat(save(claim, attempt).status()).isEqualTo(ACK_READY);
        assertThat(conversions.finishSubBatch(claim).status()).isEqualTo(ACK_READY);
        assertThat(conversions.recordPhotoFailure(claim, itemId(claim), false).status()).isEqualTo(ACK_READY);
        assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(AttachmentConversionTask.Status.SUCCEEDED);
    }

    @Test
    void 기존_저장_실패시_파생_객체를_저널에_남기고_삭제_없이_제어권을_해제한다() {
        var batch = accepted(false);
        jdbc.update("UPDATE additional_attachment_upload_batches SET conversion_route = 'DB' WHERE upload_batch_id = ?", batch.getId());
        var item = items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId()).getFirst();
        var photo = new DerivedPhotoKeys(item.getObjectKey(), "legacy/analyze", "legacy/preview", null,
                null, null, null, null, 100L, 50L, 25L, null);
        when(derivatives.createAll(eq(batch.getUploadId()), anyList(), anyList()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(List.of(photo)));
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("DB"))
                .when(source).saveConverted(eq(trip.getId()), eq(owner.getUserId()), eq(batch.getUploadId()), anyString(), anyList());

        assertThatThrownBy(() -> completion.prepareBatchPhotos(trip.getId(), owner.getUserId(),
                new AdditionalAttachmentUploadCompleteRequest(batch.getUploadId())))
                .isInstanceOf(org.springframework.dao.TransientDataAccessResourceException.class);
        verify(storage, never()).delete(anyString());
        assertThat(batches.findById(batch.getId()).orElseThrow().getStatus()).isEqualTo(AdditionalAttachmentUploadStatus.VERIFIED);
        assertThat(journal(item.getId())).filteredOn(object -> object.key().startsWith("legacy/"))
                .hasSize(2).allSatisfy(object -> assertThat(object.startedAt()).isEqualTo(NOW));
        assertUsage(100, 0);
    }

    @Test
    void 기존_보상은_최초_정리시각과_활성_첨부_키를_보존한다() {
        var batch = accepted(false);
        var claim = claim(batch, Lane.LIGHT);
        var attempt = begin(claim);
        save(claim, attempt);
        var photo = output(attempt);
        source.scheduleCleanupKeys(photo.originalKey(), List.of(photo.analyzeKey(), "legacy/unused"), "CONVERSION_COMPENSATION");
        doReturn(NOW.plusDays(2)).when(jdbc).queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
        source.scheduleCleanupKeys(photo.originalKey(), List.of(photo.analyzeKey(), "legacy/unused"), "CONVERSION_COMPENSATION");
        assertThat(journal(itemId(claim))).filteredOn(object -> object.key().equals(photo.analyzeKey()))
                .singleElement().satisfies(object -> assertThat(object.startedAt()).isNull());
        assertThat(journal(itemId(claim))).filteredOn(object -> object.key().equals("legacy/unused"))
                .singleElement().satisfies(object -> assertThat(object.startedAt()).isEqualTo(NOW));
        assertUsage(0, 100);
    }

    @Test
    void 보상시_다른_트랜잭션의_시도_시작_전에_읽은_작업을_새로고침한다() {
        var claim = claim(accepted(false), Lane.LIGHT);
        transaction.executeWithoutResult(status -> {
            assertThat(tasks.findById(claim.taskId()).orElseThrow().getCurrentItemId()).isNull();
            var otherTransaction = new TransactionTemplate(transactionManager);
            otherTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            otherTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            var attempt = otherTransaction.execute(other -> conversions.beginPhoto(claim, itemId(claim)).attempt());
            source.scheduleCleanupKeys(attempt.plannedKeys().originalKey(),
                    List.of(attempt.plannedKeys().analyzeKey()), "CONVERSION_COMPENSATION");
        });
        assertThat(journal(itemId(claim))).allSatisfy(object -> assertThat(object.startedAt()).isNull());
        assertUsage(100, 0);
    }

    @Test
    void 실패_새로고침이_출력별_기존_정리_결정을_보존한다() {
        var batch = accepted(false);
        var claim = claim(batch, Lane.LIGHT);
        var attempt = begin(claim);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> NOW.plusSeconds(calls.getAndIncrement()))
                .when(jdbc).queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
        assertThat(conversions.recordPhotoFailure(claim, itemId(claim), true).status()).isEqualTo(ACK_READY);
        var ended = executions.findById(batch.getAdditionId()).orElseThrow().getEndedAt();
        assertThat(journal(itemId(claim))).filteredOn(object -> object.key().equals(attempt.plannedKeys().analyzeKey()))
                .singleElement().satisfies(object -> assertThat(object.startedAt()).isBefore(ended));
        assertUsage(0, 0);
    }

    @Test
    void 일부_레인의_중복_성공이_잔여량을_줄이거나_중복_정산하지_않는다() {
        var batch = accepted(List.of("image/jpeg", "image/png", "image/heic"));
        var light = claim(batch, Lane.LIGHT);
        var first = begin(light);
        assertThat(save(light, first).status()).isEqualTo(CONTINUE);
        assertThat(save(light, first).status()).isEqualTo(CONTINUE);
        var task = tasks.findById(light.taskId()).orElseThrow();
        assertThat(task.getRemainingPhotos()).isEqualTo(1);
        assertThat(task.getRemainingBytes()).isEqualTo(100);
        assertUsage(300, 100);
        Long secondId = light.photos().getLast().itemId();
        var second = conversions.beginPhoto(light, secondId).attempt();
        assertThat(conversions.completePhoto(light, secondId, output(second)).status()).isEqualTo(ACK_READY);
        assertThat(tasks.findById(light.taskId()).orElseThrow().getRemainingPhotos()).isZero();
        assertThat(readyOutboxes(batch)).isEmpty();
        assertUsage(200, 200);
        var heic = claim(batch, Lane.HEIC);
        assertThat(save(heic, begin(heic)).status()).isEqualTo(ACK_READY);
        assertThat(readyOutboxes(batch)).hasSize(1);
        assertUsage(0, 400);
    }

    private AdditionalAttachmentUploadBatch accepted(boolean mixed) {
        return accepted(mixed ? List.of("image/jpeg", "image/heic") : List.of("image/jpeg"));
    }

    private AdditionalAttachmentUploadBatch accepted(List<String> contentTypes) {
        return tx(() -> {
            long bytes = contentTypes.stream().mapToLong(type -> type.equals("image/heic") ? 200 : 100).sum();
            var execution = executions.save(new AdditionalAttachmentExecution(UUID.randomUUID().toString(), trip.getId(),
                    owner.getUserId(), contentTypes.size(), NOW));
            execution.reserve(bytes);
            execution.closeInput();
            stats.findById(owner.getUserId()).orElseThrow().reserveStorage(bytes);
            var batch = batches.save(new AdditionalAttachmentUploadBatch(UUID.randomUUID().toString(), execution.getExecutionId(),
                    trip.getId(), owner.getUserId(), 1, contentTypes.size(), true));
            batch.markVerified(AttachmentConversionRoute.KAFKA);
            batch.recordRequestId("additional-task");
            for (int index = 0; index < contentTypes.size(); index++) {
                String type = contentTypes.get(index);
                items.save(new AdditionalAttachmentUploadItem(batch, index + 1, "photo-" + index,
                        type, type.equals("image/heic") ? 200L : 100L, "original/" + UUID.randomUUID()));
            }
            enqueue.enqueue(new AttachmentConversionSeed(Kind.ADDITIONAL, batch.getId(), trip.getId(), "additional-task",
                    items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId()).stream().map(item -> new AttachmentConversionSeed.Photo(
                            item.getId(), item.getContentType(), item.getSizeBytes())).toList()));
            return batch;
        });
    }

    private AttachmentConversionClaim claim(AdditionalAttachmentUploadBatch batch, Lane lane) {
        var task = tasks.findByAdditionalBatchIdAndLane(batch.getId(), lane).orElseThrow();
        return conversions.claim(task.getId(), Kind.ADDITIONAL, lane).claim();
    }

    private Attempt begin(AttachmentConversionClaim claim) {
        var result = conversions.beginPhoto(claim, itemId(claim));
        assertThat(result.status()).isEqualTo(STARTED);
        return result.attempt();
    }

    private Long itemId(AttachmentConversionClaim claim) {
        return claim.photos().getFirst().itemId();
    }

    private AttachmentConversionStepResult save(AttachmentConversionClaim claim, Attempt attempt) {
        return conversions.completePhoto(claim, itemId(claim), output(attempt));
    }

    private DerivedPhotoKeys output(Attempt attempt) {
        var keys = attempt.plannedKeys();
        return new DerivedPhotoKeys(keys.originalKey(), keys.analyzeKey(), keys.previewKey(), keys.displayKey(),
                null, null, null, null, keys.originalSizeBytes(), 50L, 25L, keys.displayKey() == null ? null : 75L);
    }

    private void expire(AttachmentConversionClaim claim) {
        jdbc.update("UPDATE attachment_conversion_tasks SET lease_until = ? WHERE task_id = ?", NOW.minusSeconds(1), claim.taskId());
    }

    private List<AdditionalAttachmentUploadOutbox> readyOutboxes(AdditionalAttachmentUploadBatch batch) {
        return outboxes.findAll().stream().filter(outbox -> outbox.getUploadBatchId().equals(batch.getId())
                && outbox.getMessageType().equals("photos_ready")).toList();
    }

    private List<AdditionalAttachmentUploadTransactionService.CleanupObject> journal(Long id) {
        return tx(() -> source.cleanupObjects(items.findById(id).orElseThrow()));
    }

    private void assertUsage(long reserved, long used) {
        var usage = stats.findById(owner.getUserId()).orElseThrow();
        assertThat(usage.getStorageReservedBytes()).isEqualTo(reserved);
        assertThat(usage.getStorageUsedBytes()).isEqualTo(used);
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private <T> T tx(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }
}
