package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.event.TripCreateResultEvent;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture.Addition;
import com.yeodam.yeodambe.trip.service.response.TripAnalysisResultClaim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.trip.service.request.TripEditPhotoDeleteCommandRequest;
import com.yeodam.yeodambe.trip.service.response.TripEditCommandResultResponse;
import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;

@SpringBootTest(properties = "app.trip.edit-lock.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripPhotoDeletionReservationIntegrationTest {
    @Autowired
    private TripPhotoDeletionService deletion;
    @Autowired
    private TripService tripViews;
    @Autowired
    private TripAttachmentDetailService details;
    @Autowired
    private TripEditCommandService commands;
    @Autowired
    private TripAnalysisExecutionService scheduling;
    @Autowired
    private TripAnalysisResultApplicationService results;
    @Autowired
    private AdditionalAttachmentUploadTransactionService uploads;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private TripObjectCleanupService cleanup;
    @MockitoBean
    private TripAttachmentStorageClient storage;

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

    @AfterEach
    void isolateClaims() {
        jdbc.update("UPDATE trip_analysis_executions SET result_next_attempt_at = '2099-01-01' WHERE trip_id = ?", tripId);
    }

    @Test
    void 분석_중_기존_사진을_중복_삭제_요청해도_예약은_하나이고_성공할_때_삭제한다() {
        fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        deletion.deleteOne(ownerId, normalPhoto);
        deletion.deleteOne(ownerId, normalPhoto);
        assertThat(isDeleted(normalPhoto)).isFalse();
        assertThat(reservationCount(normalPhoto, "PENDING")).isOne();
        assertThat(tripViews.findTripForEdit(tripId, ownerId, null).attachments().items().getFirst().pendingDeletion()).isTrue();
        assertThat(details.findDetail(ownerId, normalPhoto).pendingDeletion()).isTrue();
        complete(executionId);
        assertThat(isDeleted(normalPhoto)).isTrue();
        assertThat(reservationCount(normalPhoto, "APPLIED")).isOne();
    }

    @Test
    void 현재_입력에_없는_나중_사진의_삭제_예약은_현재_결과에_적용하지_않는다() {
        fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        Addition later = fixture.prepareAddition(tripId, ownerId);
        deletion.deleteOne(ownerId, later.photoId());
        complete(executionId);
        assertThat(isDeleted(later.photoId())).isFalse();
        assertThat(reservationCount(later.photoId(), "PENDING")).isOne();
        complete(scheduling.startNext(tripId).orElseThrow());
        assertThat(isDeleted(later.photoId())).isTrue();
    }

    @Test
    void 재분석_중에는_예약을_유지하고_최종_실패하면_기존_사진도_삭제한다() {
        fixture.prepareAddition(tripId, ownerId);
        String first = scheduling.startNext(tripId).orElseThrow();
        deletion.deleteOne(ownerId, normalPhoto);
        fail(first);
        assertThat(isDeleted(normalPhoto)).isFalse();
        String retry = scheduling.startNext(tripId).orElseThrow();
        fail(retry);
        assertThat(isDeleted(normalPhoto)).isTrue();
        assertThat(reservationCount(normalPhoto, "APPLIED")).isOne();
    }

    @Test
    void 혼합_실패에서_재분석_기회가_남은_최초_묶음이_있으면_예약을_유지한다() {
        Addition first = fixture.prepareAddition(tripId, ownerId);
        fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        deletion.deleteOne(ownerId, normalPhoto);
        jdbc.update("UPDATE additional_attachment_executions SET analysis_failure_count = 1 WHERE execution_id = ?", first.additionId());
        fail(executionId);
        assertThat(isDeleted(normalPhoto)).isFalse();
        fail(scheduling.startNext(tripId).orElseThrow());
        assertThat(isDeleted(normalPhoto)).isTrue();
    }

    @Test
    void 입력_확정_전_실패한_추가_사진의_삭제_예약도_함께_종결한다() {
        Addition pending = fixture.prepareAddition(tripId, ownerId);
        deletion.deleteOne(ownerId, pending.photoId());
        assertThat(isDeleted(pending.photoId())).isFalse();
        uploads.failExecution(pending.additionId(), "IMAGE_CONVERSION_FAILED");
        assertThat(isDeleted(pending.photoId())).isTrue();
        assertThat(reservationCount(pending.photoId(), "APPLIED")).isOne();
        assertThat(jdbc.queryForObject("SELECT cleanup_started_at IS NOT NULL FROM additional_attachment_upload_items WHERE upload_batch_id = ?",
                Boolean.class, pending.batchId())).isTrue();
    }

    @Test
    void 나중에_접수한_묶음은_기존_사진_예약의_최종_실패_확정을_계속_미루지_않는다() {
        fixture.prepareAddition(tripId, ownerId);
        String first = scheduling.startNext(tripId).orElseThrow();
        deletion.deleteOne(ownerId, normalPhoto);
        fail(first);
        Addition later = fixture.prepareAddition(tripId, ownerId);
        fail(scheduling.startNext(tripId).orElseThrow());
        assertThat(isDeleted(normalPhoto)).isTrue();
        assertThat(jdbc.queryForObject("SELECT ended_at IS NULL FROM additional_attachment_executions WHERE execution_id = ?",
                Boolean.class, later.additionId())).isTrue();
    }

    @Test
    void 최종_실패한_사진은_삼십일_보존하고_그_후에만_객체를_정리한다() {
        Addition addition = fixture.prepareAddition(tripId, ownerId);
        fail(scheduling.startNext(tripId).orElseThrow());
        fail(scheduling.startNext(tripId).orElseThrow());
        String analyzeKey = jdbc.queryForObject("SELECT analyze_storage_key FROM trip_attachments WHERE trip_attachment_id = ?",
                String.class, addition.photoId());
        cleanup.retryPending();
        verify(storage, never()).delete(analyzeKey);
        jdbc.update("UPDATE trip_attachments SET deleted_at = CURRENT_TIMESTAMP(6) - INTERVAL 31 DAY WHERE trip_attachment_id = ?",
                addition.photoId());
        LocalDateTime old = uploads.databaseTime().minusDays(31);
        String originalKey = jdbc.queryForObject("SELECT object_key FROM additional_attachment_upload_items WHERE upload_batch_id = ?",
                String.class, addition.batchId());
        List<AdditionalAttachmentUploadTransactionService.CleanupObject> journal = List.of(
                new AdditionalAttachmentUploadTransactionService.CleanupObject(originalKey, old, "AI_PROCESSING_FAILED", false));
        jdbc.update("UPDATE additional_attachment_upload_items SET cleanup_json = ?, cleanup_started_at = ? WHERE upload_batch_id = ?",
                json.writeValueAsString(journal), old, addition.batchId());
        cleanup.retryPending();
        verify(storage, atLeastOnce()).delete(analyzeKey);
        assertThat(jdbc.queryForObject("SELECT analysis_failure_count FROM additional_attachment_executions WHERE execution_id = ?",
                Integer.class, addition.additionId())).isEqualTo(2);
    }

    @Test
    void 같은_공유_명령을_재전달하면_예약과_명령_결과를_한_번만_확정한다() {
        fixture.prepareAddition(tripId, ownerId);
        scheduling.startNext(tripId).orElseThrow();
        String requestId = UUID.randomUUID().toString();
        TripEditPhotoDeleteCommandRequest request = new TripEditPhotoDeleteCommandRequest(
                requestId, json.valueToTree(List.of(normalPhoto)));
        ApiResponse<TripEditCommandResultResponse> accepted = commands.deleteAttachments(tripId, ownerId, request);
        assertThat(accepted.message()).isEqualTo("TRIP_PHOTOS_DELETE_SCHEDULED");
        assertThat(accepted.data().status()).isEqualTo("SUCCEEDED");
        assertThat(commands.deleteAttachments(tripId, ownerId, request)).isEqualTo(accepted);
        assertThat(reservationCount(normalPhoto, "PENDING")).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_edit_command_records WHERE request_id = ?",
                Integer.class, requestId)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM trip_edit_notification_outbox
                WHERE target_type = 'TRIP' AND target_id = ?
                  AND JSON_UNQUOTE(JSON_EXTRACT(event_json, '$.payload.message')) = 'TRIP_PHOTOS_DELETE_SCHEDULED'
                """, Integer.class, tripId)).isOne();
    }

    @Test
    void 소유자가_아닌_사람의_단일_삭제는_기존처럼_사진_없음으로_거절한다() {
        Long anotherOwner = fixture.createOwner();
        assertThatThrownBy(() -> deletion.deleteOne(anotherOwner, normalPhoto))
                .isInstanceOf(AttachmentNotFoundException.class);
        assertThat(isDeleted(normalPhoto)).isFalse();
    }

    @Test
    void 결과로_만든_폴더의_사진을_모두_예약_삭제하면_대표사진과_폴더_알림도_함께_정리한다() {
        Addition addition = fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        deletion.deleteBulk(ownerId, List.of(normalPhoto, addition.photoId()));
        List<Map<String, Object>> photos = List.of(normalPhoto, addition.photoId()).stream()
                .map(id -> Map.<String, Object>of("trip_attachment_id", id, "region_origin", "INFERRED",
                        "latitude", 37.5, "longitude", 127.0, "evaluation", 80)).toList();
        JsonNode body = json.valueToTree(Map.of("places", List.of(Map.of(
                "place_id", "new-place", "latitude", 37.5, "longitude", 127.0,
                "first_taken_at", "2026-09-01T12:00:00+09:00", "last_taken_at", "2026-09-01T12:00:00+09:00",
                "representative_attachment_id", normalPhoto, "attachments", photos)),
                "unclassified", List.of(), "failed", List.of()));
        TripCreateResultEvent event = new TripCreateResultEvent("process_result", tripId, executionId,
                TripCreateResultEvent.Status.COMPLETED, body, null);
        results.receive(event, json.writeValueAsString(event));
        TripAnalysisResultClaim claim = results.claim().orElseThrow();
        assertThat(results.start(claim)).isTrue();
        results.complete(claim, results.validate(claim), Map.of("new-place", "새 장소"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_detail_places WHERE trip_id = ? AND deleted_at IS NULL",
                Integer.class, tripId)).isZero();
        assertThat(jdbc.queryForObject("SELECT thumbnail_key FROM trips WHERE trip_id = ?", String.class, tripId)).isNull();
        Long placeId = jdbc.queryForObject("SELECT trip_place_id FROM trip_detail_places WHERE trip_id = ?", Long.class, tripId);
        String notification = jdbc.queryForObject("""
                SELECT event_json FROM trip_edit_notification_outbox WHERE target_type = 'TRIP' AND target_id = ?
                  AND JSON_UNQUOTE(JSON_EXTRACT(event_json, '$.payload.message')) = 'TRIP_PHOTOS_DELETED'
                """, String.class, tripId);
        JsonNode places = json.readTree(notification).path("payload").path("data").path("places");
        assertThat(places.size()).isOne();
        assertThat(places.get(0).path("tripPlaceId").asLong()).isEqualTo(placeId);
        assertThat(places.get(0).path("deleted").asBoolean()).isTrue();
        String completed = jdbc.queryForObject("""
                SELECT event_json FROM trip_edit_notification_outbox WHERE target_type = 'USER' AND target_id = ?
                  AND JSON_UNQUOTE(JSON_EXTRACT(event_json, '$.payload.data.eventType')) = 'TRIP_ANALYSIS_APPLIED'
                """, String.class, ownerId);
        JsonNode currentResult = json.readTree(completed).path("payload").path("data").path("result");
        assertThat(currentResult.path("placeFolderCount").asInt()).isZero();
        assertThat(currentResult.path("classifiedAttachmentCount").asInt()).isZero();
    }

    private void complete(String executionId) {
        String input = jdbc.queryForObject("SELECT payload FROM additional_attachment_upload_outbox WHERE analysis_execution_id = ? AND message_type = 'process'",
                String.class, executionId);
        List<Map<String, Object>> photos = StreamSupport.stream(json.readTree(input).path("attachments").spliterator(), false)
                .map(photo -> Map.<String, Object>of("trip_attachment_id", photo.path("trip_attachment_id").asLong(),
                        "region_origin", "UNKNOWN", "issue", "UNCLEAR_LOCATION")).toList();
        JsonNode result = json.valueToTree(Map.of("places", List.of(), "unclassified", photos, "failed", List.of()));
        TripCreateResultEvent event = new TripCreateResultEvent("process_result", tripId, executionId,
                TripCreateResultEvent.Status.COMPLETED, result, null);
        results.receive(event, json.writeValueAsString(event));
        TripAnalysisResultClaim claim = results.claim().orElseThrow();
        assertThat(results.start(claim)).isTrue();
        results.complete(claim, results.validate(claim), Map.of());
    }

    private void fail(String executionId) {
        TripCreateResultEvent event = new TripCreateResultEvent("process_result", tripId, executionId,
                TripCreateResultEvent.Status.FAILED, null, json.valueToTree(Map.of("code", "FAILED")));
        results.receive(event, json.writeValueAsString(event));
    }

    private boolean isDeleted(Long photoId) {
        return jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM trip_attachments WHERE trip_attachment_id = ?",
                Boolean.class, photoId);
    }

    private int reservationCount(Long photoId, String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trip_photo_deletion_reservations WHERE attachment_id = ? AND status = ?",
                Integer.class, photoId, status);
    }
}
