package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.integration.service.OutboxReceiptService;
import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionRepository;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture.Addition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "app.trip.edit-lock.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripAnalysisSchedulingIntegrationTest {
    @Autowired
    private TripAnalysisExecutionService scheduling;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private OutboxReceiptService receipts;
    @Autowired
    private AdditionalAttachmentExecutionRepository additions;
    @Autowired
    private TripAnalysisExecutionRepository analyses;

    private TripAnalysisTestFixture fixture;
    private Long ownerId;
    private Long tripId;
    private Long existingPhoto;

    @BeforeEach
    void prepareTrip() {
        fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
        existingPhoto = fixture.createPhoto(tripId, ownerId);
    }

    @Test
    void 워커를_다시_만들어도_DB에_준비된_묶음을_찾고_분석을_중복_생성하지_않는다() {
        fixture.prepareAddition(tripId, ownerId);
        new TripAnalysisSchedulingWorker(additions, scheduling, analyses).startPendingAnalyses();
        new TripAnalysisSchedulingWorker(additions, scheduling, analyses).startPendingAnalyses();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM trip_analysis_executions WHERE trip_id = ?
                """, Integer.class, tripId)).isOne();
    }

    @Test
    void 준비된_묶음을_새_실행에_합치고_전체_입력을_불변_Outbox로_남긴다() {
        Addition first = fixture.prepareAddition(tripId, ownerId);
        Addition second = fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        assertThat(executionId).isNotIn(first.additionId(), second.additionId());
        assertThat(photoIds(payload(executionId))).containsExactlyInAnyOrder(
                existingPhoto, first.photoId(), second.photoId());
        assertThat(jdbc.queryForList("""
                SELECT execution_batch_no FROM additional_attachment_upload_outbox
                WHERE analysis_execution_id = ? ORDER BY outbox_id
                """, Integer.class, executionId)).containsExactly(1, 2, 0);
        assertThat(jdbc.queryForList("""
                SELECT message_type FROM additional_attachment_upload_outbox
                WHERE analysis_execution_id = ? ORDER BY outbox_id
                """, String.class, executionId)).containsExactly("photos_ready", "photos_ready", "process");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM trip_analysis_execution_uploads WHERE execution_id = ?
                """, Integer.class, executionId)).isEqualTo(2);
    }

    @Test
    void 확정_뒤_준비된_사진과_정보_변경은_다음_분석에만_반영한다() {
        Addition first = fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        String frozen = payload(executionId);
        Addition later = fixture.prepareAddition(tripId, ownerId);
        jdbc.update("UPDATE trips SET trip_name = '바뀐여행', end_date = '2026-09-03' WHERE trip_id = ?", tripId);
        jdbc.update("UPDATE trip_regions SET latitude = 35.0 WHERE trip_id = ?", tripId);
        assertThat(scheduling.startNext(tripId)).isEmpty();
        assertThat(payload(executionId)).isEqualTo(frozen);
        assertThat(photoIds(frozen)).containsExactlyInAnyOrder(existingPhoto, first.photoId());
        fixture.completeAnalysis(executionId);
        String next = scheduling.startNext(tripId).orElseThrow();
        assertThat(next).isNotEqualTo(executionId);
        JsonNode input = json.readTree(payload(next));
        assertThat(photoIds(payload(next))).containsExactlyInAnyOrder(existingPhoto, first.photoId(), later.photoId());
        assertThat(input.path("trip_name").asString()).isEqualTo("바뀐여행");
        assertThat(input.path("period").path("end_date").asString()).isEqualTo("2026-09-03");
        assertThat(input.path("regions").get(0).path("latitude").asDouble()).isEqualTo(35.0);
    }

    @Test
    void 두_시작_워커가_경쟁해도_활성_분석은_하나만_생긴다() throws Exception {
        fixture.prepareAddition(tripId, ownerId);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> first = workers.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return scheduling.startNext(tripId);
            });
            Future<?> second = workers.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return scheduling.startNext(tripId);
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM trip_analysis_executions WHERE trip_id = ? AND ended_at IS NULL
                """, Integer.class, tripId)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM additional_attachment_upload_outbox WHERE trip_id = ? AND message_type = 'process'
                """, Integer.class, tripId)).isOne();
    }

    @Test
    void 기존_분석과_저장_보류가_끝나기_전에는_새_분석을_시작하지_않는다() {
        Addition legacy = fixture.prepareAddition(tripId, ownerId);
        jdbc.update("UPDATE additional_attachment_executions SET processing_mode = 'LEGACY' WHERE execution_id = ?",
                legacy.additionId());
        fixture.prepareAddition(tripId, ownerId);
        assertThat(scheduling.startNext(tripId)).isEmpty();
        jdbc.update("""
                UPDATE additional_attachment_executions SET status = 'COMPLETED', ended_at = CURRENT_TIMESTAMP(6)
                WHERE execution_id = ?
                """, legacy.additionId());
        String executionId = scheduling.startNext(tripId).orElseThrow();
        jdbc.update("UPDATE trip_analysis_executions SET status = 'APPLY_BLOCKED' WHERE execution_id = ?", executionId);
        fixture.prepareAddition(tripId, ownerId);
        assertThat(scheduling.startNext(tripId)).isEmpty();
    }

    @Test
    void 배치가_없는_process의_발행_확인을_중복_반영하지_않고_입력도_바꾸지_않는다() {
        fixture.prepareAddition(tripId, ownerId);
        String executionId = scheduling.startNext(tripId).orElseThrow();
        String frozen = payload(executionId);
        Map<String, Object> process = jdbc.queryForMap("""
                SELECT outbox_id, topic FROM additional_attachment_upload_outbox
                WHERE analysis_execution_id = ? AND message_type = 'process'
                """, executionId);
        String outboxId = process.get("outbox_id").toString();
        String topic = (String) process.get("topic");
        assertThatThrownBy(() -> receipts.observe("additional_attachment_upload_outbox", outboxId,
                new OutboxReceipt(topic, tripId.toString(), "{}", Instant.now())))
                .isInstanceOf(IllegalArgumentException.class);
        jdbc.update("UPDATE trips SET trip_name = '발행뒤수정' WHERE trip_id = ?", tripId);
        OutboxReceipt receipt = new OutboxReceipt(topic, tripId.toString(), frozen, Instant.now());
        receipts.observe("additional_attachment_upload_outbox", outboxId, receipt);
        receipts.observe("additional_attachment_upload_outbox", outboxId, receipt);
        assertThat(payload(executionId)).isEqualTo(frozen);
        assertThat(jdbc.queryForObject("""
                SELECT process_published_at IS NOT NULL FROM trip_analysis_executions WHERE execution_id = ?
                """, Boolean.class, executionId)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM additional_attachment_upload_outbox
                WHERE analysis_execution_id = ? AND message_type = 'process'
                """, Integer.class, executionId)).isOne();
    }

    private String payload(String executionId) {
        return jdbc.queryForObject("""
                SELECT payload FROM additional_attachment_upload_outbox
                WHERE analysis_execution_id = ? AND message_type = 'process'
                """, String.class, executionId);
    }

    private Set<Long> photoIds(String payload) {
        return StreamSupport.stream(json.readTree(payload).path("attachments").spliterator(), false)
                .map(photo -> photo.path("trip_attachment_id").asLong()).collect(Collectors.toSet());
    }
}
