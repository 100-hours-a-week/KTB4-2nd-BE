package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.common.exception.UserNotFoundException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadCompleteRequest;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadUrlRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.invocation.InvocationOnMock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {"app.trip.edit-lock.enabled=true", "app.trip.edit-lock.wait-time=100ms"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AdditionalUploadCompletionMutationIntegrationTest {
    @Autowired
    private AdditionalAttachmentUploadCompletionService completion;
    @Autowired
    private AdditionalAttachmentUploadUrlService legacyAdmission;
    @Autowired
    private AdditionalAttachmentAdmissionTransactionService admissions;
    @Autowired
    private TripMutationCoordinator mutations;
    @Autowired
    private TripShareUserRepository shares;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TripEditLockProperties properties;
    @Autowired
    @Qualifier("tripEditRedissonClient")
    private RedissonClient redis;
    @MockitoSpyBean
    private AdditionalAttachmentUploadTransactionService transactions;
    @MockitoBean
    private TripAttachmentStorageClient storage;

    private Long tripId;
    private Long ownerId;
    private Long actorId;
    private final AtomicInteger commits = new AtomicInteger();

    @BeforeEach
    void prepareTrip() {
        TripAnalysisTestFixture fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        actorId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
        LocalDateTime now = LocalDateTime.now();
        shares.saveAndFlush(new TripShareUser(
                tripId, ownerId, actorId, TripSharePermission.WRITE, now.plusDays(1), now
        ));
        commits.set(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 권한_철회나_READ_변경_후에도_최초_접수자가_완료를_커밋한다(boolean revoke) {
        AdditionalAttachmentUploadBatch batch = prepareBatch(true);
        if (revoke) {
            jdbc.update("UPDATE trip_share_users SET deleted_at = CURRENT_TIMESTAMP(6) WHERE trip_id = ?", tripId);
        } else {
            jdbc.update("UPDATE trip_share_users SET permission = 'READ' WHERE trip_id = ?", tripId);
        }
        validStorage();
        doAnswer(this::observeMutation).when(transactions).markVerified(anyLong(), anyLong(), anyString());

        assertThat(completion.complete(tripId, actorId, request(batch)).totalAttachments()).isEqualTo(1);

        assertThat(commits).hasValue(1);
        assertThat(status(batch)).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject(
                "SELECT input_closed FROM additional_attachment_executions WHERE execution_id = ?",
                Boolean.class, batch.getAdditionId())).isTrue();
        assertOutsideMutation();
    }

    @Test
    void 기존_소유자_완료는_커밋까지_락을_유지하고_중복_검증을_생략한다() {
        AdditionalAttachmentUploadBatch batch = prepareBatch(false);
        validStorage();
        doAnswer(this::observeMutation).when(transactions).markVerified(anyLong(), anyLong(), anyString());

        completion.complete(tripId, ownerId, request(batch));
        completion.complete(tripId, ownerId, request(batch));

        assertThat(commits).hasValue(1);
        assertThat(status(batch)).isEqualTo("VERIFIED");
        verify(storage, times(1)).size(anyString());
        verify(storage, times(1)).open(anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 다른_접수자의_완료는_락_경합과_무관하게_기존_권한_오류로_거부한다(boolean v3) throws Exception {
        AdditionalAttachmentUploadBatch batch = prepareBatch(v3);
        Long foreignId = v3 ? ownerId : actorId;
        Class<? extends RuntimeException> expected = v3
                ? InvalidAttachmentUploadException.class : TripNotFoundException.class;

        assertRejectedWhileLocked(batch, foreignId, expected);

        assertThat(status(batch)).isEqualTo("PENDING");
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 최초_접수자도_락_경합시_S3_검사와_완료_변경을_실행하지_않는다(boolean v3) throws Exception {
        AdditionalAttachmentUploadBatch batch = prepareBatch(v3);
        validStorage();

        assertRejectedWhileLocked(batch, v3 ? actorId : ownerId, TripEditLockRejectedException.class);

        assertThat(status(batch)).isEqualTo("PENDING");
        verifyNoInteractions(storage);
    }

    @Test
    void 탈퇴한_접수자는_락_경합_전에_완료를_거부한다() throws Exception {
        AdditionalAttachmentUploadBatch batch = prepareBatch(true);
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP(6) WHERE user_id = ?", actorId);

        assertRejectedWhileLocked(batch, actorId, UserNotFoundException.class);

        verifyNoInteractions(storage);
        assertThat(status(batch)).isEqualTo("PENDING");
    }

    @Test
    void S3_검사_중_탈퇴하면_완료_확정시_다시_검사해_예약과_대기_상태를_보존한다() {
        AdditionalAttachmentUploadBatch batch = prepareBatch(true);
        validStorage();
        doAnswer(call -> {
            assertOutsideMutation();
            jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP(6) WHERE user_id = ?", actorId);
            return 12L;
        }).when(storage).size(anyString());

        assertThatThrownBy(() -> completion.complete(tripId, actorId, request(batch)))
                .isInstanceOf(UserNotFoundException.class);

        assertThat(status(batch)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT storage_reserved_bytes FROM user_stats WHERE user_id = ?",
                Long.class, ownerId)).isEqualTo(12L);
    }

    @Test
    void 파일_검사_실패는_락_안에서_실패와_예약_해제를_커밋한다() {
        AdditionalAttachmentUploadBatch batch = prepareBatch(false);
        doAnswer(call -> {
            assertOutsideMutation();
            return 11L;
        }).when(storage).size(anyString());
        doAnswer(this::observeMutation).when(transactions).failExecution(anyString(), anyString());

        assertThatThrownBy(() -> completion.complete(tripId, ownerId, request(batch)))
                .isInstanceOf(InvalidAttachmentUploadException.class);

        assertThat(commits).hasValue(1);
        assertThat(status(batch)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT storage_reserved_bytes FROM user_stats WHERE user_id = ?",
                Long.class, ownerId)).isZero();
        assertOutsideMutation();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 종료된_V3_완료는_거부하고_기존_방식의_완료_재응답은_보존한다(boolean v3) {
        AdditionalAttachmentUploadBatch batch = prepareBatch(v3);
        jdbc.update("UPDATE additional_attachment_upload_batches SET status = 'COMPLETED' WHERE upload_batch_id = ?",
                batch.getId());
        jdbc.update("""
                UPDATE additional_attachment_executions
                SET status = 'COMPLETED', ended_at = CURRENT_TIMESTAMP(6) WHERE execution_id = ?
                """, batch.getAdditionId());

        if (v3) {
            assertThatThrownBy(() -> completion.complete(tripId, actorId, request(batch)))
                    .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        } else {
            assertThat(completion.complete(tripId, ownerId, request(batch)).totalAttachments()).isEqualTo(1);
        }

        verifyNoInteractions(storage);
        assertThat(status(batch)).isEqualTo("COMPLETED");
    }

    private AdditionalAttachmentUploadBatch prepareBatch(boolean v3) {
        AdditionalAttachmentUploadUrlRequest request = new AdditionalAttachmentUploadUrlRequest(
                UUID.randomUUID().toString(), 1, 1, true, List.of(
                        new AdditionalAttachmentUploadUrlRequest.Attachment("photo.jpg", "image/jpeg", 12L)
                )
        );
        if (v3) {
            return mutations.execute(tripId, () -> admissions.prepareBatch(tripId, actorId, request));
        }
        return legacyAdmission.prepareBatch(tripId, ownerId, request);
    }

    private void validStorage() {
        doAnswer(call -> {
            assertOutsideMutation();
            return 12L;
        }).when(storage).size(anyString());
        doAnswer(call -> {
            assertOutsideMutation();
            return new ByteArrayInputStream(new byte[]{(byte) 255, (byte) 216, (byte) 255});
        }).when(storage).open(anyString());
    }

    private void assertRejectedWhileLocked(
            AdditionalAttachmentUploadBatch batch, Long actor, Class<? extends RuntimeException> expected
    ) throws Exception {
        RLock lock = lock();
        lock.lock();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> response = executor.submit(() -> assertThatThrownBy(
                    () -> completion.complete(tripId, actor, request(batch))).isInstanceOf(expected));
            response.get(5, TimeUnit.SECONDS);
        } finally {
            lock.unlock();
        }
    }

    private Object observeMutation(InvocationOnMock call) throws Throwable {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(lock().isHeldByCurrentThread()).isTrue();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                assertThat(lock().isHeldByCurrentThread()).isTrue();
                commits.incrementAndGet();
            }
        });
        return call.callRealMethod();
    }

    private void assertOutsideMutation() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(lock().isHeldByCurrentThread()).isFalse();
    }

    private RLock lock() {
        return redis.getLock(properties.namespace() + ":lock:trip:mutation:" + tripId);
    }

    private AdditionalAttachmentUploadCompleteRequest request(AdditionalAttachmentUploadBatch batch) {
        return new AdditionalAttachmentUploadCompleteRequest(batch.getUploadId());
    }

    private String status(AdditionalAttachmentUploadBatch batch) {
        return jdbc.queryForObject(
                "SELECT status FROM additional_attachment_upload_batches WHERE upload_batch_id = ?",
                String.class, batch.getId()
        );
    }
}
