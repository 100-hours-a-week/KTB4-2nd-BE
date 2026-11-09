package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentIdsException;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture.Addition;
import com.yeodam.yeodambe.trip.fixture.TripUploadTestErrorMessage;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.service.response.TripPhotoDeletionResult;
import com.yeodam.yeodambe.user.service.UserStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "app.trip.edit-lock.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripPhotoDeletionReservationPersistenceTest {
    @Autowired
    private TripPhotoDeletionService deletion;
    @Autowired
    private TripPhotoDeletionReservationService reservations;
    @Autowired
    private TripMutationCoordinator mutations;
    @Autowired
    private TripAttachmentRepository attachments;
    @Autowired
    private UserStatsService stats;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    private TripAnalysisTestFixture fixture;
    private Long ownerId;
    private Long tripId;
    private Long normalPhoto;

    @BeforeEach
    void prepareTrip() {
        fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
        normalPhoto = fixture.createPhoto(tripId, ownerId);
    }

    @Test
    void 중복_예약은_사진과_사용량을_바꾸지_않고_예약_알림을_한_번만_저장한다() {
        Addition pending = fixture.prepareAddition(tripId, ownerId);
        refreshStats();
        long before = usedBytes();
        long sequence = sequence();

        deletion.deleteOne(ownerId, normalPhoto);
        deletion.deleteOne(ownerId, normalPhoto);

        assertThat(deleted(normalPhoto)).isFalse();
        assertThat(reservationCount(normalPhoto, "PENDING")).isEqualTo(1);
        assertThat(usedBytes()).isEqualTo(before);
        assertThat(sequence()).isEqualTo(sequence + 1);
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(attachments.findPendingDeletionIds(List.of(normalPhoto, pending.photoId())))
                .containsExactly(normalPhoto);
        assertThat(attachments.countVisibleByClassification(tripId, ClassificationStatus.UNCLASSIFIED)).isEqualTo(1);
        assertThat(attachments.countVisibleByClassification(tripId, ClassificationStatus.ACTIVE)).isZero();
        assertThat(attachments.findAllUndeletedForDeletion(List.of(normalPhoto, pending.photoId()))).hasSize(2);
        assertThat(attachments.findDeletionTripIds(List.of(normalPhoto, pending.photoId()))).containsExactly(tripId);
    }

    @Test
    void 적용_실패는_사진과_예약과_정산과_알림을_함께_롤백하고_현재_입력만_재적용한다() {
        fixture.prepareAddition(tripId, ownerId);
        deletion.deleteOne(ownerId, normalPhoto);
        Addition later = fixture.prepareAddition(tripId, ownerId);
        deletion.deleteOne(ownerId, later.photoId());
        refreshStats();
        long before = usedBytes();
        long notifications = notificationCount();
        long sequence = sequence();

        assertThatThrownBy(() -> mutations.run(tripId, () -> {
            reservations.applyForInput(tripId, Set.of(normalPhoto));
            throw new IllegalStateException(TripUploadTestErrorMessage.ANALYSIS_OUTBOX_FAILURE.message());
        })).isInstanceOf(IllegalStateException.class);
        assertThat(deleted(normalPhoto)).isFalse();
        assertThat(reservationCount(normalPhoto, "PENDING")).isEqualTo(1);
        assertThat(usedBytes()).isEqualTo(before);
        assertThat(notificationCount()).isEqualTo(notifications);
        assertThat(sequence()).isEqualTo(sequence);

        mutations.run(tripId, () -> reservations.applyForInput(tripId, Set.of(normalPhoto)));
        assertThat(deleted(normalPhoto)).isTrue();
        assertThat(deleted(later.photoId())).isFalse();
        assertThat(reservationCount(normalPhoto, "APPLIED")).isEqualTo(1);
        assertThat(reservationCount(later.photoId(), "PENDING")).isEqualTo(1);
        assertThat(usedBytes()).isEqualTo(before - 12);
        assertThat(notificationCount()).isEqualTo(notifications + 1);
        assertThat(sequence()).isEqualTo(sequence + 1);

        assertThat(mutations.execute(tripId, () -> reservations.applyForInput(tripId, Set.of(normalPhoto)))).isNull();
        assertThat(usedBytes()).isEqualTo(before - 12);
        assertThat(notificationCount()).isEqualTo(notifications + 1);
        assertThat(sequence()).isEqualTo(sequence + 1);
    }

    @Test
    void 관련된_모든_묶음이_종료돼야_기존_사진의_최종_실패_예약을_적용한다() {
        Addition first = fixture.prepareAddition(tripId, ownerId);
        Addition second = fixture.prepareAddition(tripId, ownerId);
        deletion.deleteOne(ownerId, normalPhoto);
        endAddition(first.additionId());
        mutations.run(tripId, () -> reservations.applyFinalFailures(tripId, Set.of(normalPhoto)));
        assertThat(deleted(normalPhoto)).isFalse();
        assertThat(reservationCount(normalPhoto, "PENDING")).isEqualTo(1);

        endAddition(second.additionId());
        mutations.run(tripId, () -> reservations.applyFinalFailures(tripId, Set.of(normalPhoto)));
        assertThat(deleted(normalPhoto)).isTrue();
        assertThat(reservationCount(normalPhoto, "APPLIED")).isEqualTo(1);
    }

    @Test
    void 여행_삭제_후에는_예약만_종결하고_사용량이나_알림을_다시_변경하지_않는다() {
        fixture.prepareAddition(tripId, ownerId);
        deletion.deleteOne(ownerId, normalPhoto);
        jdbc.update("UPDATE trips SET deleted_at = CURRENT_TIMESTAMP(6) WHERE trip_id = ?", tripId);
        jdbc.update("""
                UPDATE trip_attachments photo JOIN files file ON file.file_id = photo.file_id
                SET photo.deleted_at = CURRENT_TIMESTAMP(6), file.deleted_at = CURRENT_TIMESTAMP(6)
                WHERE photo.trip_id = ?
                """, tripId);
        refreshStats();
        long notifications = notificationCount();
        long sequence = sequence();
        assertThat(usedBytes()).isZero();

        mutations.run(tripId, () -> reservations.settleEndedAdditions(tripId));
        mutations.run(tripId, () -> reservations.settleEndedAdditions(tripId));
        assertThat(reservationCount(normalPhoto, "APPLIED")).isEqualTo(1);
        assertThat(usedBytes()).isZero();
        assertThat(notificationCount()).isEqualTo(notifications);
        assertThat(sequence()).isEqualTo(sequence);
    }

    @Test
    void 다른_사용자의_단건_삭제와_잘못된_일괄_ID는_저장_전에_거절한다() {
        Long otherId = fixture.createOwner();
        assertThatThrownBy(() -> deletion.deleteOne(otherId, normalPhoto))
                .isInstanceOf(AttachmentNotFoundException.class);
        assertThatThrownBy(() -> deletion.deleteOne(ownerId, Long.MAX_VALUE))
                .isInstanceOf(AttachmentNotFoundException.class);
        assertThatThrownBy(() -> deletion.deleteBulk(ownerId, List.of(normalPhoto, normalPhoto)))
                .isInstanceOf(InvalidAttachmentIdsException.class);
        assertThat(deleted(normalPhoto)).isFalse();
        assertThat(reservationCount(normalPhoto, "PENDING")).isZero();
        assertThat(notificationCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 서로_다른_사용자_요청의_예약은_각자의_사진과_연속_번호로_완료_알림을_남긴다(boolean sameRequestId) {
        Long otherPhoto = fixture.createPhoto(tripId, ownerId);
        fixture.prepareAddition(tripId, ownerId);
        Long secondActor = sameRequestId ? fixture.createOwner() : ownerId;
        String firstRequest = "first-request";
        String secondRequest = sameRequestId ? firstRequest : "second-request";
        if (sameRequestId) {
            jdbc.update("""
                    INSERT INTO trip_share_users (trip_id, user_id, grantee_user_id, permission, expires_at)
                    VALUES (?, ?, ?, 'WRITE', DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY))
                    """, tripId, ownerId, secondActor);
        }
        mutations.run(tripId, () -> reservations.reserve(tripId, ownerId, firstRequest, List.of(normalPhoto)));
        mutations.run(tripId, () -> reservations.reserve(tripId, secondActor, secondRequest, List.of(otherPhoto)));
        refreshStats();
        long beforeBytes = usedBytes();
        long beforeSequence = sequence();

        assertThatThrownBy(() -> mutations.run(tripId, () -> {
            reservations.applyForInput(tripId, Set.of(normalPhoto, otherPhoto));
            throw new IllegalStateException(TripUploadTestErrorMessage.ANALYSIS_OUTBOX_FAILURE.message());
        })).isInstanceOf(IllegalStateException.class);
        assertThat(deleted(normalPhoto)).isFalse();
        assertThat(deleted(otherPhoto)).isFalse();
        assertThat(reservationCount(normalPhoto, "PENDING")).isEqualTo(1);
        assertThat(reservationCount(otherPhoto, "PENDING")).isEqualTo(1);
        assertThat(notificationCount()).isEqualTo(2);
        assertThat(usedBytes()).isEqualTo(beforeBytes);
        assertThat(sequence()).isEqualTo(beforeSequence);

        TripPhotoDeletionResult result = mutations.execute(tripId,
                () -> reservations.applyForInput(tripId, Set.of(normalPhoto, otherPhoto)));

        List<JsonNode> events = jdbc.queryForList("""
                SELECT event_json FROM trip_edit_notification_outbox
                WHERE target_type = 'TRIP' AND target_id = ? AND event_json LIKE '%TRIP_PHOTOS_DELETED%'
                ORDER BY notification_id
                """, String.class, tripId).stream().map(json::readTree).toList();
        assertThat(events).hasSize(2);
        JsonNode first = events.getFirst().path("payload").path("data");
        JsonNode second = events.getLast().path("payload").path("data");
        assertThat(first.path("requestId").asString()).isEqualTo(firstRequest);
        assertThat(second.path("requestId").asString()).isEqualTo(secondRequest);
        assertThat(first.path("attachmentIds").size()).isEqualTo(1);
        assertThat(first.path("attachmentIds").get(0).asLong()).isEqualTo(normalPhoto);
        assertThat(second.path("attachmentIds").size()).isEqualTo(1);
        assertThat(second.path("attachmentIds").get(0).asLong()).isEqualTo(otherPhoto);
        assertThat(first.path("sequence").asLong()).isEqualTo(beforeSequence + 1);
        assertThat(second.path("sequence").asLong()).isEqualTo(beforeSequence + 2);
        assertThat(sequence()).isEqualTo(beforeSequence + 2);
        assertThat(result.attachmentIds()).containsExactly(normalPhoto, otherPhoto);
        assertThat(result.attachmentCount()).isEqualTo(second.path("attachmentCount").asLong());
    }

    private void refreshStats() {
        mutations.run(tripId, () -> stats.refreshFromActiveTrips(ownerId));
    }

    private boolean deleted(Long photoId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT photo.deleted_at IS NOT NULL AND file.deleted_at IS NOT NULL
                FROM trip_attachments photo JOIN files file ON file.file_id = photo.file_id
                WHERE photo.trip_attachment_id = ?
                """, Boolean.class, photoId));
    }

    private long reservationCount(Long photoId, String status) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM trip_photo_deletion_reservations WHERE attachment_id = ? AND status = ?
                """, Long.class, photoId, status);
    }

    private long usedBytes() {
        return jdbc.queryForObject("SELECT storage_used_bytes FROM user_stats WHERE user_id = ?", Long.class, ownerId);
    }

    private long sequence() {
        return jdbc.queryForObject("SELECT change_sequence FROM trips WHERE trip_id = ?", Long.class, tripId);
    }

    private long notificationCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM trip_edit_notification_outbox WHERE target_type = 'TRIP' AND target_id = ?
                """, Long.class, tripId);
    }

    private void endAddition(String additionId) {
        jdbc.update("""
                UPDATE additional_attachment_executions SET status = 'FAILED', ended_at = CURRENT_TIMESTAMP(6)
                WHERE execution_id = ?
                """, additionId);
    }
}
