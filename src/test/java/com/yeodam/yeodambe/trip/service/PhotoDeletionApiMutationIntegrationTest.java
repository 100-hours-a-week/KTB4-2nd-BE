package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.WritePermissionRequiredException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.controller.TripAttachmentController;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.service.request.BulkAttachmentDeleteRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
class PhotoDeletionApiMutationIntegrationTest {
    @Autowired
    private TripAttachmentController controller;
    @Autowired
    private TripAnalysisExecutionService scheduling;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private TripAttachmentStorageClient storage;
    @MockitoBean
    private TripEditNotificationPublisher publisher;
    @Autowired
    private TripEditLockProperties lockProperties;
    @Autowired
    @Qualifier("tripEditRedissonClient")
    private RedissonClient redis;
    private Long ownerId;
    private Long tripId;
    private Long photoId;

    @BeforeEach
    void prepareAnalysis() {
        TripAnalysisTestFixture fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
        photoId = fixture.createPhoto(tripId, ownerId);
        fixture.prepareAddition(tripId, ownerId);
        scheduling.startNext(tripId).orElseThrow();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void REST_단건과_일괄_삭제는_분석_중인_사진을_중복_예약해도_한_번만_접수한다(boolean bulk) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none").subject(ownerId.toString()).build();
        long previousSequence = jdbc.queryForObject("SELECT change_sequence FROM trips WHERE trip_id = ?",
                Long.class, tripId);
        for (int request = 0; request < 2; request++) {
            ResponseEntity<Void> response;
            if (bulk) {
                response = controller.deleteAttachments(new BulkAttachmentDeleteRequest(List.of(photoId)), jwt);
            } else {
                response = controller.deleteAttachment(photoId, jwt);
            }
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }

        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM trip_attachments WHERE trip_attachment_id = ?",
                Boolean.class, photoId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_photo_deletion_reservations WHERE attachment_id = ? AND status = 'PENDING'",
                Long.class, photoId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT change_sequence FROM trips WHERE trip_id = ?",
                Long.class, tripId)).isEqualTo(previousSequence + 1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM trip_edit_notification_outbox
                WHERE target_type = 'TRIP' AND target_id = ?
                  AND JSON_UNQUOTE(JSON_EXTRACT(event_json, '$.payload.message')) = 'TRIP_PHOTOS_DELETE_SCHEDULED'
                """, Long.class, tripId)).isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 타인_사진의_여행_락이_경합해도_단건과_일괄_삭제는_기존_권한_오류를_유지한다(boolean bulk) {
        Long anotherOwner = new TripAnalysisTestFixture(jdbc).createOwner();
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none").subject(anotherOwner.toString()).build();
        RLock lock = redis.getLock(lockProperties.namespace() + ":lock:trip:mutation:" + tripId);
        lock.lock();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<ResponseEntity<Void>> response = executor.submit(() -> {
                if (bulk) {
                    return controller.deleteAttachments(new BulkAttachmentDeleteRequest(List.of(photoId)), jwt);
                }
                return controller.deleteAttachment(photoId, jwt);
            });
            Class<? extends RuntimeException> expected = bulk
                    ? WritePermissionRequiredException.class : AttachmentNotFoundException.class;
            assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(expected);
        } finally {
            lock.unlock();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_photo_deletion_reservations WHERE attachment_id = ?",
                Long.class, photoId)).isZero();
    }
}
