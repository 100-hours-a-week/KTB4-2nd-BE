package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.service.request.AttachmentRestoreRequest;
import com.yeodam.yeodambe.trip.service.request.BulkAttachmentRestoreRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

@SpringBootTest(properties = {"app.trip.edit-lock.enabled=true", "app.trip.edit-lock.wait-time=100ms"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TripRestorationLockIntegrationTest {
    @Autowired
    private TripAttachmentRestoreService restoration;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TripDetailPlaceRepository places;
    @Autowired
    private TripEditLockProperties properties;
    @Autowired
    @Qualifier("tripEditRedissonClient")
    private RedissonClient redis;
    @MockitoSpyBean
    private TripAttachmentRepository attachments;

    private Long ownerId;
    private Long tripId;
    private Long photoId;
    private Long placeId;

    @BeforeEach
    void prepareRestorablePhoto() {
        TripAnalysisTestFixture fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
        photoId = fixture.createPhoto(tripId, ownerId);
        jdbc.update("UPDATE trip_attachments SET classification_status = 'UNCLASSIFIED', issue = 'BLURRY' WHERE trip_attachment_id = ?", photoId);
        LocalDateTime now = LocalDateTime.now();
        placeId = places.saveAndFlush(TripDetailPlace.fromAnalysis(tripId, 1, "복구 장소", BigDecimal.ZERO,
                BigDecimal.ZERO, now, now, null)).getId();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 단건과_일괄_복구의_실제_UPDATE는_여행_락을_커밋까지_유지한다(boolean bulk) {
        AtomicBoolean committedWithLock = new AtomicBoolean();
        Answer<?> delegate = mockingDetails(attachments).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(lock().isHeldByCurrentThread()).isTrue();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    committedWithLock.set(lock().isHeldByCurrentThread());
                }
            });
            return delegate.answer(invocation);
        }).when(attachments).restoreIfUnclassified(
                anyLong(), anyLong(), anyLong(), nullable(Long.class), anyLong(), any(LocalDateTime.class)
        );

        restore(bulk);

        assertThat(committedWithLock).isTrue();
        assertThat(lock().isLocked()).isFalse();
        assertThat(jdbc.queryForObject("SELECT issue FROM trip_attachments WHERE trip_attachment_id = ?",
                String.class, photoId)).isEqualTo("NONE");
        assertThat(jdbc.queryForObject("SELECT change_sequence FROM trips WHERE trip_id = ?",
                Long.class, tripId)).isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 여행_락을_다른_처리자가_보유하면_단건과_일괄_복구도_대기_한도를_지킨다(boolean bulk) throws Exception {
        RLock lock = redis.getLock(properties.namespace() + ":lock:trip:mutation:" + tripId);
        lock.lock();
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Future<?> result = worker.submit(() -> restore(bulk));
            assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(TripEditLockRejectedException.class);
            assertUnchanged();
        } finally {
            lock.unlock();
        }
        restore(bulk);
        assertThat(jdbc.queryForObject("SELECT issue FROM trip_attachments WHERE trip_attachment_id = ?", String.class, photoId))
                .isEqualTo("NONE");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void Redis_연결이_끊기면_단건과_일괄_복구도_DB_전용_경로로_우회하지_않는다(boolean bulk) {
        redis.shutdown();
        assertThatThrownBy(() -> restore(bulk)).isInstanceOf(TripEditLockRejectedException.class);
        assertUnchanged();
    }

    private void restore(boolean bulk) {
        if (bulk) {
            restoration.restoreBulk(ownerId, new BulkAttachmentRestoreRequest(
                    List.of(new BulkAttachmentRestoreRequest.Item(photoId, placeId))));
        } else {
            restoration.restoreOne(ownerId, photoId, new AttachmentRestoreRequest(placeId));
        }
    }

    private RLock lock() {
        return redis.getLock(properties.namespace() + ":lock:trip:mutation:" + tripId);
    }

    private void assertUnchanged() {
        assertThat(jdbc.queryForObject("SELECT classification_status FROM trip_attachments WHERE trip_attachment_id = ?",
                String.class, photoId)).isEqualTo("UNCLASSIFIED");
        assertThat(jdbc.queryForObject("SELECT issue FROM trip_attachments WHERE trip_attachment_id = ?", String.class, photoId))
                .isEqualTo("BLURRY");
        assertThat(jdbc.queryForObject("SELECT change_sequence FROM trips WHERE trip_id = ?", Long.class, tripId)).isZero();
    }
}
