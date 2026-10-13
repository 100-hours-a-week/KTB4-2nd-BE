package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
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
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InitialAttachmentUploadFlowIntegrationTest {
    @Autowired
    private InitialAttachmentUploadTransactionService transactions;
    @Autowired
    private TripAnalysisResultService results;
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



    @Test
    void Kafka_재시도는_같은_ID와_payload를_유지하고_실패로_바꾸지_않는다() {
        var batch = prepare(1, 1, true);
        accept(batch);
        convert();
        var claim = transactions.claimDelivery(batch.batchId()).orElseThrow();
        transactions.finishDelivery(claim, false);
        jdbc.update("UPDATE initial_attachment_upload_batches SET delivery_next_attempt_at = NULL WHERE upload_batch_id = ?", batch.batchId());
        var retried = transactions.claimDelivery(batch.batchId()).orElseThrow();
        assertThat(retried.outboxId()).isEqualTo(claim.outboxId());
        assertThat(retried.payload()).isEqualTo(claim.payload());
        assertThat(execution().isTerminal()).isFalse();
        transactions.finishDelivery(claim, true);
        assertThat(batches.findById(batch.batchId()).orElseThrow().getDeliveryStatus()).isEqualTo(InitialAttachmentDeliveryStatus.PUBLISHING);
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
            transactions.finishDelivery(transactions.claimDelivery(batch.batchId()).orElseThrow(), true);
            executionId = batch.executionId();
        }
        var process = transactions.claimProcess(executionId).orElseThrow();
        assertThat(json.readTree(process.payload()).path("attachments").size()).isEqualTo(200);
        assertThat(process.payload().getBytes(StandardCharsets.UTF_8).length).isLessThan(1024 * 1024);
    }



    private TripKafkaDeliveryClaim publishSingleBatch() {
        var batch = prepare(1, 1, true);
        accept(batch);
        convert();
        transactions.finishDelivery(transactions.claimDelivery(batch.batchId()).orElseThrow(), true);
        var process = transactions.claimProcess(batch.executionId()).orElseThrow();
        transactions.finishDelivery(process, true);
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
