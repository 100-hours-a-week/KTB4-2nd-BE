package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties;
import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionRoute;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.AttachmentConversionTaskRepository;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.request.AttachmentConversionSeed;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim.Attempt;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadBatchSnapshot;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "app.trip.edit-lock.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AttachmentConversionMutationBoundaryIntegrationTest {
    @MockitoSpyBean
    private AttachmentConversionTransactionService transactions;
    @Autowired
    private AttachmentConversionService conversions;
    @Autowired
    private InitialAttachmentUploadTransactionService uploads;
    @Autowired
    private AttachmentConversionEnqueueService enqueue;
    @Autowired
    private AttachmentConversionTaskRepository tasks;
    @Autowired
    private InitialAttachmentUploadBatchRepository batches;
    @Autowired
    private TripCreationExecutionRepository executions;
    @Autowired
    private TripRepository trips;
    @Autowired
    private UserRepository users;
    @Autowired
    private UserStatsRepository stats;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private AttachmentConversionProperties properties;
    @Autowired
    private TripEditLockProperties lockProperties;
    @Autowired
    @Qualifier("tripEditRedissonClient")
    private RedissonClient redis;

    @Test
    void 워커는_저장소_호출_밖에서_여행_락을_잡고_DB_커밋까지_유지한다() throws Exception {
        AttachmentConversionTask task = accepted();
        String lockKey = lockProperties.namespace() + ":lock:trip:mutation:" + task.getTripId();
        AtomicBoolean savedInTransaction = new AtomicBoolean();
        AtomicBoolean savedWithLock = new AtomicBoolean();
        AtomicBoolean committedWithLock = new AtomicBoolean();
        AtomicBoolean convertedOutsideTransaction = new AtomicBoolean();
        AtomicBoolean convertedOutsideLock = new AtomicBoolean();
        TripAttachmentDerivativeService photos = mock(TripAttachmentDerivativeService.class);
        when(photos.processConversionPhoto(any(), any(), any(), any())).thenAnswer(invocation -> {
            convertedOutsideTransaction.set(!TransactionSynchronizationManager.isActualTransactionActive());
            convertedOutsideLock.set(!redis.getLock(lockKey).isHeldByCurrentThread());
            Attempt attempt = invocation.getArgument(2);
            return CompletableFuture.completedFuture(output(attempt.plannedKeys()));
        });
        doAnswer(invocation -> {
            savedInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            savedWithLock.set(redis.getLock(lockKey).isHeldByCurrentThread());
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        committedWithLock.set(redis.getLock(lockKey).isHeldByCurrentThread());
                    }
                });
            }
            return invocation.callRealMethod();
        }).when(transactions).completePhoto(any(), anyLong(), any());
        AttachmentConversionWorker worker = new AttachmentConversionWorker(
                conversions, photos, properties, Duration.ofMillis(100));
        try {
            worker.process(task.getId(), Kind.INITIAL, Lane.LIGHT, properties.topics().initialLight(), 0)
                    .get(10, TimeUnit.SECONDS);

            assertThat(tasks.findById(task.getId()).orElseThrow().getStatus())
                    .isEqualTo(AttachmentConversionTask.Status.SUCCEEDED);
            assertThat(convertedOutsideTransaction).isTrue();
            assertThat(convertedOutsideLock).isTrue();
            assertThat(savedInTransaction).isTrue();
            assertThat(savedWithLock).isTrue();
            assertThat(committedWithLock).isTrue();
            assertThat(redis.getLock(lockKey).isLocked()).isFalse();
        } finally {
            worker.drain().get(10, TimeUnit.SECONDS);
        }
    }

    private AttachmentConversionTask accepted() {
        User user = users.save(new User(UUID.randomUUID() + "@conversion-boundary.test", "변환 경계"));
        stats.save(new UserStats(user));
        Trip trip = trips.save(new Trip(user.getUserId(), "변환 경계", LocalDate.now(), LocalDate.now()));
        List<InitialAttachmentUploadUrlRequest.Attachment> photos = List.of(
                new InitialAttachmentUploadUrlRequest.Attachment("boundary.jpg", "image/jpeg", 100L));
        InitialAttachmentUploadBatchSnapshot prepared = uploads.prepareBatch(
                trip.getId(), user.getUserId(), new InitialAttachmentUploadUrlRequest(1, 1, true, photos));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            batches.findById(prepared.batchId()).orElseThrow()
                    .accept(LocalDateTime.now(), AttachmentConversionRoute.KAFKA);
            executions.findById(prepared.executionId()).orElseThrow().closeInput();
            enqueue.enqueue(new AttachmentConversionSeed(
                    Kind.INITIAL,
                    prepared.batchId(),
                    trip.getId(),
                    "conversion-boundary-test",
                    prepared.photos().stream().map(photo -> new AttachmentConversionSeed.Photo(
                            photo.itemId(), photo.contentType(), photo.sizeBytes())).toList()
            ));
        });
        return tasks.findByInitialBatchIdAndLane(prepared.batchId(), Lane.LIGHT).orElseThrow();
    }

    private static DerivedPhotoKeys output(DerivedPhotoKeys keys) {
        return new DerivedPhotoKeys(
                keys.originalKey(),
                keys.analyzeKey(),
                keys.previewKey(),
                keys.displayKey(),
                null,
                null,
                null,
                null,
                keys.originalSizeBytes(),
                50L,
                25L,
                keys.displayKey() == null ? null : 75L
        );
    }
}
