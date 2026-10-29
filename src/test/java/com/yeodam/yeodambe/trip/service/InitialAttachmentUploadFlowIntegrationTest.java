package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;
import java.time.Instant;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.websocket.service.WebSocketEventPublisher;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEvent;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.event.TripCreateResultEvent;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.request.*;
import com.yeodam.yeodambe.trip.service.response.*;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InitialAttachmentUploadFlowIntegrationTest {
    @Autowired
    private InitialAttachmentUploadTransactionService transactions;
    @Autowired
    private TripAnalysisResultService results;
    @Autowired
    private TripAnalysisResultWorker worker;
    @Autowired
    private TripCreationExecutionRepository executions;
    @Autowired
    private InitialAttachmentUploadBatchRepository batches;
    @Autowired
    private TripRepository trips;
    @Autowired
    private TripRegionRepository regions;
    @Autowired
    private UserRepository users;
    @Autowired
    private UserStatsRepository stats;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private TripPlaceNameService names;
    @Autowired
    private TripWithdrawalService withdrawal;
    @Autowired private TripCreationOutboxRepository outboxes;
    @MockitoBean private WebSocketEventPublisher notificationPublisher;
    @Autowired private TransactionTemplate transaction;
    private User owner;
    private Trip trip;

    @BeforeEach
    void prepareUser() {
        owner = users.save(new User(UUID.randomUUID() + "@yeodam.test", "업로드회원"));
        stats.save(new UserStats(owner));
        trip = trips.save(new Trip(owner.getUserId(), "직접 업로드", LocalDate.now(), LocalDate.now()));
        regions.save(new TripRegion(trip, "11000", "서울특별시", BigDecimal.ONE, BigDecimal.TEN));
        when(names.resolve(any(), any(), any(), any())).thenReturn(Map.of());
    }

    @AfterEach
    void finishExecution() {
        executions.findByTripId(trip.getId()).ifPresent(execution ->
                transactions.failExecution(execution.getExecutionId(), "TEST_FINISHED"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 결과_수신으로_공개상태가_바뀔때만_커밋후_반영중_알림을_보낸다(boolean alreadyFinalizing) {
        String raw = prepareResultNotification();
        org.mockito.Mockito.clearInvocations(notificationPublisher);
        if (alreadyFinalizing) {
            transaction.executeWithoutResult(status -> executions.findForUpdate(execution().getExecutionId())
                    .orElseThrow().updateDisplayStatus(TripDisplayStatus.FINALIZING, null));
        }
        var event = json.readValue(raw, TripCreateResultEvent.class);
        transaction.executeWithoutResult(status -> {
            results.receiveKafkaResult(event, raw);
            verifyNoInteractions(notificationPublisher);
        });
        results.receiveKafkaResult(event, raw);

        if (alreadyFinalizing) {
            verifyNoInteractions(notificationPublisher);
        } else {
            var captor = ArgumentCaptor.forClass(WebSocketEvent.class);
            verify(notificationPublisher).publish(captor.capture());
            verifyNoMoreInteractions(notificationPublisher);
            var notification = (TripProcessingStatusNotification) captor.getValue().payload().data();
            assertThat(notification.status()).isEqualTo(TripProcessingStatusResponse.Status.FINALIZING);
            assertThat(notification.statusVersion()).isEqualTo(execution().getStatusVersion());
            assertThat(notification.currentStep()).isNull();
            assertThat(notification.result()).isNull();
            assertThat(notification.error()).isNull();
        }
    }

    @Test
    void 결과_수신이_롤백되면_반영중_알림도_보내지_않는다() {
        String raw = prepareResultNotification();
        org.mockito.Mockito.clearInvocations(notificationPublisher);
        transaction.executeWithoutResult(status -> {
            results.receiveKafkaResult(json.readValue(raw, TripCreateResultEvent.class), raw);
            status.setRollbackOnly();
        });

        verifyNoInteractions(notificationPublisher);
        assertThat(execution().getResultRawPayload()).isNull();
        assertThat(execution().getDisplayStatus()).isEqualTo(TripDisplayStatus.PROCESSING);
        assertThat(execution().getStatusVersion()).isEqualTo(1);
    }

    private String prepareResultNotification() {
        var batch = prepare(1, 1, true);
        accept(batch);
        convert();
        confirmBatch(batch.batchId());
        var process = processOutbox(batch.executionId());
        confirm(process);
        return resultPayload(json.readTree(process.getPayload()), false);
    }

    @Test
    void 배치_접수_변환_발행_결과_원문_최종저장을_분리한다() {
        var first = prepare(1, 2, false);
        assertThat(accept(first).status()).isEqualTo(TripProcessingStatusResponse.Status.PROCESSING);
        convert();
        assertThat(execution().getProcessPayload()).isNull();
        confirmBatch(first.batchId());
        assertThat(execution().getProcessPayload()).isNull();
        var last = prepare(2, 2, true);
        accept(last);
        convert();
        assertThat(execution().getProcessPayload()).isNull();
        confirmBatch(last.batchId());
        var process = processOutbox(first.executionId());
        var wire = json.readTree(process.getPayload());
        assertThat(wire.path("type").asString()).isEqualTo("process");
        assertThat(wire.path("attachments").size()).isEqualTo(2);
        assertThat(wire.has("eventType")).isFalse();
        confirm(process);
        String raw = resultPayload(wire, false);
        results.receiveKafkaResult(json.readValue(raw, TripCreateResultEvent.class), raw);
        assertThat(execution().getStatus()).isEqualTo(TripCreationExecution.Status.RESULT_APPLYING);
        assertThat(trips.findById(trip.getId()).orElseThrow().getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSING);
        worker.applyPendingResults();
        assertThat(trips.findById(trip.getId()).orElseThrow().getProcessingStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(accept(last).status()).isEqualTo(TripProcessingStatusResponse.Status.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT storage_used_bytes FROM user_stats WHERE user_id = ?", Long.class, owner.getUserId())).isEqualTo(24L);
        assertThat(jdbc.queryForObject("SELECT storage_reserved_bytes FROM user_stats WHERE user_id = ?", Long.class, owner.getUserId())).isZero();
        results.receiveKafkaResult(json.readValue(raw, TripCreateResultEvent.class), raw);
        assertThat(execution().getResultAttempts()).isEqualTo(1);
    }

    @Test
    void 결과_사진이_누락되면_부분저장_없이_실패하고_예약을_한번만_반환한다() {
        var batch = prepare(1, 1, true);
        accept(batch);
        convert();
        confirmBatch(batch.batchId());
        var process = processOutbox(batch.executionId());
        confirm(process);
        String raw = resultPayload(json.readTree(process.getPayload()), true);
        results.receiveKafkaResult(json.readValue(raw, TripCreateResultEvent.class), raw);
        worker.applyPendingResults();
        assertThat(execution().getStatus()).isEqualTo(TripCreationExecution.Status.FAILED);
        assertThat(execution().getErrorCode()).isEqualTo("AI_RESULT_INTEGRITY_FAILED");
        assertThat(execution().getResultFailureReason()).isEqualTo("결과의 사진 집합이 실행과 다릅니다.");
        assertThat(jdbc.queryForObject("SELECT storage_reserved_bytes FROM user_stats WHERE user_id = ?", Long.class, owner.getUserId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_detail_places WHERE trip_id = ?", Integer.class, trip.getId())).isZero();
        transactions.failExecution(batch.executionId(), "AGAIN");
        assertThat(execution().getErrorCode()).isEqualTo("AI_RESULT_INTEGRITY_FAILED");
    }

    @Test
    void Kafka_재시도는_같은_ID와_payload를_유지하고_실패로_바꾸지_않는다() {
        var batch = prepare(1, 1, true);
        accept(batch);
        convert();
        var outbox = outboxes.findById(batches.findById(batch.batchId()).orElseThrow().getOutboxId()).orElseThrow();
        String id = outbox.getId();
        String payload = outbox.getPayload();
        assertThat(execution().isTerminal()).isFalse();
        assertThat(execution().getProcessPublishedAt()).isNull();
        assertThat(outboxes.findById(id).orElseThrow().getPayload()).isEqualTo(payload);
        confirm(outbox);
        confirm(outbox);
        assertThat(outboxes.findById(id).orElseThrow().getPayload()).isEqualTo(payload);
        assertThat(batches.findById(batch.batchId()).orElseThrow().getDeliveryStatus())
                .isEqualTo(InitialAttachmentDeliveryStatus.SENT);
    }

    @Test
    void 결과_반영_일시_실패는_원문을_보존하고_세번_후_종료한다() {
        var process = publishSingleBatch();
        String raw = resultPayload(json.readTree(process.getPayload()), false);
        results.receiveKafkaResult(json.readValue(raw, TripCreateResultEvent.class), raw);
        when(names.resolve(any(), any(), any(), any()))
                .thenThrow(new TransientDataAccessResourceException("테스트 DB 일시 장애"));
        for (int attempt = 1; attempt <= 3; attempt++) {
            worker.applyPendingResults();
            assertThat(execution().getResultAttempts()).isEqualTo(attempt);
            assertThat(execution().getResultRawPayload()).isEqualTo(raw);
            jdbc.update("UPDATE trip_creation_executions SET result_next_attempt_at = NULL WHERE execution_id = ?", process.getExecutionId());
        }
        assertThat(execution().getErrorCode()).isEqualTo("RESULT_APPLY_RETRY_EXHAUSTED");
        assertThat(execution().isReservationReleased()).isTrue();
    }

    @Test
    void 취소_후_늦은_결과와_이전_선점은_최종_저장을_하지_못한다() {
        var process = publishSingleBatch();
        String raw = resultPayload(json.readTree(process.getPayload()), false);
        var event = json.readValue(raw, TripCreateResultEvent.class);
        results.receiveKafkaResult(event, raw);
        var claim = results.claimResult().orElseThrow();
        var result = results.validateForApplication(claim);
        transactions.cancelExecution(trip.getId(), owner.getUserId());
        assertThatThrownBy(() -> results.saveKafkaCompleted(claim, result, Map.of()))
                .isInstanceOf(CancellationException.class);
        results.receiveKafkaResult(event, raw);
        assertThat(execution().getStatus()).isEqualTo(TripCreationExecution.Status.CANCELED);
        assertThat(execution().isCancelPending()).isTrue();
        assertThat(execution().isReservationReleased()).isTrue();
    }

    @Test
    void 결과_수신_상한은_첫_발행부터_한시간이고_원문_인계_후는_제외한다() {
        var process = publishSingleBatch();
        jdbc.update("UPDATE trip_creation_executions SET process_published_at = CURRENT_TIMESTAMP(6) - INTERVAL 3599 SECOND WHERE execution_id = ?", process.getExecutionId());
        transactions.failExpiredExecution(process.getExecutionId(), true);
        assertThat(execution().isTerminal()).isFalse();
        jdbc.update("UPDATE trip_creation_executions SET process_published_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 HOUR WHERE execution_id = ?", process.getExecutionId());
        String raw = resultPayload(json.readTree(process.getPayload()), false);
        results.receiveKafkaResult(json.readValue(raw, TripCreateResultEvent.class), raw);
        transactions.failExpiredExecution(process.getExecutionId(), true);
        assertThat(execution().getStatus()).isEqualTo(TripCreationExecution.Status.RESULT_APPLYING);
        worker.applyPendingResults();
        assertThat(execution().getAccountedBytes()).isEqualTo(12L);
    }

    @Test
    void 한시간_미수신_후_늦은_완료는_실패를_되돌리지_않는다() {
        var process = publishSingleBatch();
        jdbc.update("UPDATE trip_creation_executions SET process_published_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 HOUR WHERE execution_id = ?", process.getExecutionId());
        transactions.failExpiredExecution(process.getExecutionId(), true);
        String raw = resultPayload(json.readTree(process.getPayload()), false);
        results.receiveKafkaResult(json.readValue(raw, TripCreateResultEvent.class), raw);
        assertThat(execution().getStatus()).isEqualTo(TripCreationExecution.Status.FAILED);
        assertThat(execution().getErrorCode()).isEqualTo("AI_RESULT_TIMEOUT");
        assertThat(execution().getResultRawPayload()).isNull();
    }

    @Test
    void 최대_이백장_process_payload는_기본_Kafka_한메가바이트보다_작다() {
        String executionId = null;
        for (int number = 1; number <= 20; number++) {
            var declarations = IntStream.range(0, 10)
                    .mapToObj(i -> new InitialAttachmentUploadUrlRequest.Attachment("photo.jpg", "image/jpeg", 12L))
                    .toList();
            var batch = transactions.prepareBatch(trip.getId(), owner.getUserId(),
                    new InitialAttachmentUploadUrlRequest(number, 200, number == 20, declarations));
            accept(batch);
            convert();
            confirmBatch(batch.batchId());
            executionId = batch.executionId();
        }
        var process = processOutbox(executionId);
        assertThat(json.readTree(process.getPayload()).path("attachments").size()).isEqualTo(200);
        assertThat(process.getPayload().getBytes(StandardCharsets.UTF_8).length).isLessThan(1024 * 1024);
    }

    @Test
    void 결과_선점은_시도를_늘리지_않고_만료된_token은_시작할_수_없다() {
        var process = publishSingleBatch();
        String raw = resultPayload(json.readTree(process.getPayload()), false);
        results.receiveKafkaResult(json.readValue(raw, TripCreateResultEvent.class), raw);
        var old = results.claimResult().orElseThrow();
        assertThat(execution().getResultAttempts()).isZero();
        jdbc.update("UPDATE trip_creation_executions SET result_lease_until = CURRENT_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE execution_id = ?", process.getExecutionId());
        var current = results.claimResult().orElseThrow();
        assertThat(results.startResultAttempt(old)).isFalse();
        assertThat(results.startResultAttempt(current)).isTrue();
        assertThat(execution().getResultAttempts()).isEqualTo(1);
        assertThat(execution().getResultAttemptStartedAt()).isNotNull();
        assertThat(results.startResultAttempt(current)).isFalse();
    }

    @Test
    void 탈퇴는_진행중_원본_예약과_정리_의도를_남기고_실행을_종료한다() {
        var batch = prepare(1, 1, true);
        accept(batch);
        var claim = transactions.claimBatch().orElseThrow();
        withdrawal.withdrawAll(owner.getUserId(), LocalDateTime.now());
        assertThat(execution().getStatus()).isEqualTo(TripCreationExecution.Status.CANCELED);
        assertThat(execution().isReservationReleased()).isTrue();
        assertThat(transactions.renewLease(claim)).isFalse();
        assertThat(jdbc.queryForObject("SELECT cleanup_started_at IS NOT NULL FROM initial_attachment_upload_items WHERE upload_item_id = ?",
                Boolean.class, batch.photos().getFirst().itemId())).isTrue();
    }

    private void confirmBatch(Long id) {
        confirm(outboxes.findById(batches.findById(id).orElseThrow().getOutboxId()).orElseThrow());
    }

    private TripCreationOutbox processOutbox(String executionId) {
        return outboxes.findById(executions.findById(executionId).orElseThrow().getProcessOutboxId()).orElseThrow();
    }

    private void confirm(TripCreationOutbox outbox) {
        transactions.recordPublished(outbox.getId(), new OutboxReceipt(
                outbox.getTopic(), outbox.getTripId().toString(), outbox.getPayload(), Instant.now()));
    }

    private TripCreationOutbox publishSingleBatch() {
        var batch = prepare(1, 1, true);
        accept(batch);
        convert();
        confirmBatch(batch.batchId());
        var process = processOutbox(batch.executionId());
        confirm(process);
        return process;
    }

    private InitialAttachmentUploadBatchSnapshot prepare(
            int number,
            int count,
            boolean last
    ) {
        return transactions.prepareBatch(trip.getId(), owner.getUserId(), new InitialAttachmentUploadUrlRequest(
                number, count, last, List.of(new InitialAttachmentUploadUrlRequest.Attachment("photo.jpg", "image/jpeg", 12L))));
    }

    private TripProcessingStatusResponse accept(InitialAttachmentUploadBatchSnapshot batch) {
        return transactions.startProcessing(trip.getId(), owner.getUserId(), batch.uploadId());
    }

    private void convert() {
        var claim = transactions.claimBatch().orElseThrow();
        for (var photo : claim.photos()) {
            List<String> keys = transactions.startPhotoAttempt(claim, photo.itemId());
            transactions.savePhotoSuccess(claim, photo.itemId(), new DerivedPhotoKeys(photo.objectKey(), keys.get(0), keys.get(1),
                    null, null, null, null, null, 12L, 8L, 4L, null));
        }
        transactions.completeBatch(claim);
    }

    private TripCreationExecution execution() {
        return executions.findByTripId(trip.getId()).orElseThrow();
    }

    private String resultPayload(
            JsonNode process,
            boolean missingPhoto
    ) {
        var root = json.createObjectNode();
        root.put("type", "process_result").put("trip_id", trip.getId()).put("execution_id", execution().getExecutionId()).put("status", "COMPLETED");
        root.putNull("error");
        var result = root.putObject("result");
        result.putArray("places");
        result.putArray("failed");
        var photos = result.putArray("unclassified");
        if (!missingPhoto) {
            for (var photo : process.path("attachments")) {
                photos.addObject().put("trip_attachment_id", photo.path("trip_attachment_id").asLong())
                        .put("issue", "BLURRY").put("region_origin", "UNKNOWN").put("evaluation", 31);
            }
        }
        return json.writeValueAsString(root);
    }
}
