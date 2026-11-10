package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.BulkRestoreFailedException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.service.request.AttachmentRestoreRequest;
import com.yeodam.yeodambe.trip.service.request.BulkAttachmentRestoreRequest;
import com.yeodam.yeodambe.trip.service.request.TripUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"app.trip.edit-lock.enabled=true", "app.trip.edit-lock.wait-time=100ms"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripMutationAuthorizationIntegrationTest {
    @Autowired
    private TripUpdateService updates;
    @Autowired
    private TripDeletionService deletion;
    @Autowired
    private TripAttachmentRestoreService restoration;
    @Autowired
    private TripDetailPlaceRepository places;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TripEditLockProperties properties;
    @Autowired
    @Qualifier("tripEditRedissonClient")
    private RedissonClient redis;

    private Long ownerId;
    private Long anotherOwnerId;
    private Long tripId;
    private Long photoId;
    private Long placeId;

    @BeforeEach
    void prepareOwnedTrip() {
        TripAnalysisTestFixture fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        anotherOwnerId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
        photoId = fixture.createPhoto(tripId, ownerId);
        jdbc.update("UPDATE trip_attachments SET classification_status = 'UNCLASSIFIED', issue = 'BLURRY' WHERE trip_attachment_id = ?",
                photoId);
        LocalDateTime now = LocalDateTime.now();
        placeId = places.saveAndFlush(TripDetailPlace.fromAnalysis(
                tripId, 1, "복구 장소", BigDecimal.ZERO, BigDecimal.ZERO, now, now, null
        )).getId();
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void 타인_여행의_변경은_락_경합보다_기존_접근_거절을_먼저_반환한다(Operation operation) {
        Class<? extends RuntimeException> expected = switch (operation) {
            case RESTORE_ONE -> AttachmentNotFoundException.class;
            case RESTORE_BULK -> BulkRestoreFailedException.class;
            default -> TripNotFoundException.class;
        };
        assertRejectedWhileLocked(operation, anotherOwnerId, expected);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void 소유자의_변경은_여행_락이_경합하면_접수하지_않는다(Operation operation) {
        assertRejectedWhileLocked(operation, ownerId, TripEditLockRejectedException.class);
    }

    private void assertRejectedWhileLocked(
            Operation operation, Long actorId, Class<? extends RuntimeException> expected
    ) {
        RLock lock = redis.getLock(properties.namespace() + ":lock:trip:mutation:" + tripId);
        lock.lock();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> response = executor.submit(() -> invoke(operation, actorId));
            assertThatThrownBy(() -> response.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(expected)
                    .satisfies(failure -> {
                        if (failure.getCause() instanceof TripEditLockRejectedException rejection) {
                            assertThat(rejection.reason()).isEqualTo(TripEditLockRejectedException.Reason.CONTENDED);
                        }
                    });
        } finally {
            lock.unlock();
        }
        assertThat(jdbc.queryForObject("SELECT trip_name FROM trips WHERE trip_id = ?", String.class, tripId))
                .isEqualTo("분석여행");
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM trips WHERE trip_id = ?", Boolean.class, tripId))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT is_favorite FROM trips WHERE trip_id = ?", Boolean.class, tripId))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT change_sequence FROM trips WHERE trip_id = ?", Long.class, tripId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT classification_status FROM trip_attachments WHERE trip_attachment_id = ?",
                String.class, photoId)).isEqualTo("UNCLASSIFIED");
    }

    private void invoke(Operation operation, Long actorId) {
        switch (operation) {
            case UPDATE -> updates.updateTrip(tripId, actorId, new TripUpdateRequest("변경여행", null, null, null));
            case FAVORITE_ADD -> updates.registerFavorite(tripId, actorId);
            case FAVORITE_REMOVE -> updates.removeFavorite(tripId, actorId);
            case DELETE -> deletion.delete(tripId, actorId);
            case RESTORE_ONE -> restoration.restoreOne(actorId, photoId, new AttachmentRestoreRequest(placeId));
            case RESTORE_BULK -> restoration.restoreBulk(actorId, new BulkAttachmentRestoreRequest(List.of(
                    new BulkAttachmentRestoreRequest.Item(photoId, placeId)
            )));
        }
    }

    private enum Operation {
        UPDATE, FAVORITE_ADD, FAVORITE_REMOVE, DELETE, RESTORE_ONE, RESTORE_BULK
    }
}
