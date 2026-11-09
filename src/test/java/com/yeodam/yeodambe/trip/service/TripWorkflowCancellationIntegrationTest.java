package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture.Addition;
import com.yeodam.yeodambe.trip.fixture.TripUploadTestErrorMessage;
import com.yeodam.yeodambe.user.service.UserStatsService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = "app.trip.edit-lock.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripWorkflowCancellationIntegrationTest {
    @Autowired
    private TripDeletionService deletion;
    @Autowired
    private TripPhotoDeletionService photoDeletion;
    @Autowired
    private TripAnalysisExecutionService scheduling;
    @Autowired
    private TripMutationCoordinator mutations;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoSpyBean
    private UserStatsService stats;
    @MockitoBean
    private TripAttachmentStorageClient storage;
    private TripAnalysisTestFixture fixture;
    private Long ownerId;
    private Long tripId;
    private Long normalPhoto;
    private Addition addition;
    private String analysisId;

    @BeforeEach
    void prepareActiveWorkflow() {
        fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
        normalPhoto = fixture.createPhoto(tripId, ownerId);
        addition = fixture.prepareAddition(tripId, ownerId);
        analysisId = scheduling.startNext(tripId).orElseThrow();
        photoDeletion.deleteOne(ownerId, normalPhoto);
        mutations.run(tripId, () -> stats.refreshFromActiveTrips(ownerId));
    }

    @Test
    void 진행_중인_분석과_묶음과_삭제_예약을_함께_취소하고_객체는_즉시_삭제하지_않는다() {
        deletion.delete(tripId, ownerId);

        assertThat(tripDeleted()).isTrue();
        assertThat(analysisState()).isEqualTo("CANCELED");
        assertThat(additionState()).isEqualTo("CANCELED");
        assertThat(reservationState()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_attachments WHERE trip_id = ? AND deleted_at IS NULL",
                Integer.class, tripId)).isZero();
        assertThat(jdbc.queryForObject("SELECT storage_used_bytes + storage_reserved_bytes FROM user_stats WHERE user_id = ?",
                Long.class, ownerId)).isZero();
        assertThat(jdbc.queryForObject("SELECT requires_remote_termination_confirmation FROM trip_analysis_executions WHERE execution_id = ?",
                Boolean.class, analysisId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT cleanup_started_at IS NOT NULL FROM additional_attachment_upload_items WHERE upload_batch_id = ?",
                Boolean.class, addition.batchId())).isTrue();
        int notifications = notificationCount();
        assertThatThrownBy(() -> deletion.delete(tripId, ownerId)).isInstanceOf(TripNotFoundException.class);
        assertThat(notificationCount()).isEqualTo(notifications);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void 소유자가_아니면_작업과_예약과_사용량을_바꾸지_않는다() {
        Long strangerId = fixture.createOwner();
        Map<String, Object> beforeStats = jdbc.queryForMap("SELECT * FROM user_stats WHERE user_id = ?", ownerId);
        int notifications = notificationCount();

        assertThatThrownBy(() -> deletion.delete(tripId, strangerId)).isInstanceOf(TripNotFoundException.class);

        assertUnchanged(beforeStats, notifications);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void 정산이_실패하면_작업_취소와_사진과_예약과_알림을_함께_롤백하고_다시_삭제한다() {
        Map<String, Object> beforeStats = jdbc.queryForMap("SELECT * FROM user_stats WHERE user_id = ?", ownerId);
        int notifications = notificationCount();
        doThrow(new IllegalStateException(TripUploadTestErrorMessage.DATABASE_UNAVAILABLE.message()))
                .when(stats).refreshFromActiveTrips(ownerId);

        assertThatThrownBy(() -> deletion.delete(tripId, ownerId)).isInstanceOf(IllegalStateException.class);

        assertUnchanged(beforeStats, notifications);
        doCallRealMethod().when(stats).refreshFromActiveTrips(ownerId);
        deletion.delete(tripId, ownerId);
        assertThat(tripDeleted()).isTrue();
        assertThat(analysisState()).isEqualTo("CANCELED");
        assertThat(additionState()).isEqualTo("CANCELED");
        verify(storage, never()).delete(anyString());
    }

    private void assertUnchanged(Map<String, Object> beforeStats, int notifications) {
        assertThat(tripDeleted()).isFalse();
        assertThat(analysisState()).isEqualTo("AI_REQUESTED");
        assertThat(additionState()).isEqualTo("AI_QUEUED");
        assertThat(reservationState()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_attachments WHERE trip_id = ? AND deleted_at IS NULL",
                Integer.class, tripId)).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT * FROM user_stats WHERE user_id = ?", ownerId)).isEqualTo(beforeStats);
        assertThat(notificationCount()).isEqualTo(notifications);
    }

    private boolean tripDeleted() {
        return jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM trips WHERE trip_id = ?", Boolean.class, tripId);
    }

    private String analysisState() {
        return jdbc.queryForObject("SELECT status FROM trip_analysis_executions WHERE execution_id = ?", String.class, analysisId);
    }

    private String additionState() {
        return jdbc.queryForObject("SELECT status FROM additional_attachment_executions WHERE execution_id = ?", String.class, addition.additionId());
    }

    private String reservationState() {
        return jdbc.queryForObject("SELECT status FROM trip_photo_deletion_reservations WHERE attachment_id = ?", String.class, normalPhoto);
    }

    private int notificationCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM trip_edit_notification_outbox
                WHERE (target_type = 'TRIP' AND target_id = ?) OR (target_type = 'USER' AND target_id = ?)
                """, Integer.class, tripId, ownerId);
    }
}
