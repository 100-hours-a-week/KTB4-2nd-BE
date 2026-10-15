package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadBatchSnapshot;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"app.outbox.receipt-listener-enabled=false"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripCreationCdcIntegrationTest {
    @Autowired private InitialAttachmentUploadTransactionService transactions;
    @Autowired private TripCreationOutboxRepository outboxes;
    @Autowired private TripCreationExecutionRepository executions;
    @Autowired private TripRepository trips;
    @Autowired private TripRegionRepository regions;
    @Autowired private UserRepository users;
    @Autowired private UserStatsRepository stats;
    @Autowired private TransactionTemplate transaction;
    @Autowired private JdbcTemplate jdbc;
    private User owner;
    private Trip trip;

    @BeforeEach
    void setUp() {
        owner = users.save(new User(UUID.randomUUID() + "@cdc.test", "CDC"));
        stats.save(new UserStats(owner));
        trip = trips.save(new Trip(owner.getUserId(), "CDC 여행", LocalDate.now(), LocalDate.now()));
        regions.save(new TripRegion(trip, "11000", "서울", BigDecimal.ONE, BigDecimal.TEN));
    }

    @Test
    void processWaitsForEveryBatchAndConfirmationIsIdempotent() {
        convert(1, false);
        convert(2, true);
        var ready = outboxes.findAll().stream().filter(value -> value.getTripId().equals(trip.getId())).toList();
        assertThat(ready).hasSize(2);
        transactions.recordPublished(ready.getFirst().getId(), receipt(ready.getFirst()));
        assertThat(execution().getProcessOutboxId()).isNull();
        transactions.recordPublished(ready.getLast().getId(), receipt(ready.getLast()));
        String processId = execution().getProcessOutboxId();
        assertThat(processId).isNotBlank();
        transactions.recordPublished(ready.getLast().getId(), receipt(ready.getLast()));
        assertThat(outboxes.findAll().stream().filter(value -> value.getTripId().equals(trip.getId()))).hasSize(3);
        var process = outboxes.findById(processId).orElseThrow();
        transactions.recordPublished(process.getId(), receipt(process));
        var firstTime = execution().getProcessPublishedAt();
        transactions.recordPublished(process.getId(), receipt(process));
        assertThat(execution().getProcessPublishedAt()).isEqualTo(firstTime);
    }

    @Test
    void delayedReceiptStartsTimeoutAtBrokerAppendTimeAndDuplicateDoesNotExtendIt() {
        convert(1, false);
        convert(2, true);
        outboxes.findAll().stream().filter(value -> value.getTripId().equals(trip.getId()))
                .forEach(value -> transactions.recordPublished(value.getId(), receipt(value)));
        var process = outboxes.findById(execution().getProcessOutboxId()).orElseThrow();
        var appended = Instant.now().minusSeconds(3601).truncatedTo(ChronoUnit.MILLIS);
        transactions.recordPublished(process.getId(), new OutboxReceipt(
                process.getTopic(), trip.getId().toString(), process.getPayload(), appended));
        transactions.recordPublished(process.getId(), receipt(process));
        assertThat(jdbc.queryForObject("SELECT UNIX_TIMESTAMP(process_published_at) * 1000 FROM trip_creation_executions WHERE execution_id = ?",
                BigDecimal.class, process.getExecutionId()).longValueExact()).isEqualTo(appended.toEpochMilli());
        transactions.failExpiredExecution(process.getExecutionId(), true);
        assertThat(execution().getErrorCode()).isEqualTo("AI_RESULT_TIMEOUT");
    }

    @Test
    void rollbackDoesNotLeaveAnEventAndWrongKeyDoesNotConfirm() {
        convert(1, false);
        var outbox = outboxes.findAll().stream().filter(value -> value.getTripId().equals(trip.getId())).findFirst().orElseThrow();
        assertThatThrownBy(() -> transactions.recordPublished(outbox.getId(), new OutboxReceipt(
                outbox.getTopic(), "wrong", outbox.getPayload(), Instant.now()))).isInstanceOf(IllegalArgumentException.class);
        assertThat(outboxes.findById(outbox.getId()).orElseThrow().getPublishedAt()).isNull();
        transaction.executeWithoutResult(status -> {
            transactions.recordPublished(outbox.getId(), receipt(outbox));
            status.setRollbackOnly();
        });
        assertThat(outboxes.findById(outbox.getId()).orElseThrow().getPublishedAt()).isNull();
    }

    @Test
    void rolledBackConversionLeavesNoCdcEvent() {
        transaction.executeWithoutResult(status -> {
            convert(1, false);
            assertThat(outboxes.findAll().stream()
                    .filter(value -> value.getTripId().equals(trip.getId()))).hasSize(1);
            status.setRollbackOnly();
        });

        assertThat(outboxes.findAll().stream()
                .filter(value -> value.getTripId().equals(trip.getId()))).isEmpty();
        assertThat(executions.findByTripId(trip.getId())).isEmpty();
    }

    @Test
    void cancelledExecutionDoesNotEnqueueProcessOnLateConfirmation() {
        convert(1, false);
        convert(2, true);
        transactions.cancelExecution(trip.getId(), owner.getUserId());
        var ready = outboxes.findAll().stream().filter(value -> value.getTripId().equals(trip.getId())).toList();
        ready.forEach(value -> transactions.recordPublished(value.getId(), receipt(value)));
        assertThat(execution().getStatus()).isEqualTo(TripCreationExecution.Status.CANCELED);
        assertThat(execution().getProcessOutboxId()).isNull();
    }

    @Test
    void concurrentLastBatchConfirmationsCreateOneProcess() throws Exception {
        convert(1, false);
        convert(2, true);
        var ready = outboxes.findAll().stream().filter(value -> value.getTripId().equals(trip.getId())).toList();
        var waiting = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var tasks = ready.stream().map(value -> workers.submit(() -> {
                waiting.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                transactions.recordPublished(value.getId(), receipt(value));
                return null;
            })).toList();
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }
        assertThat(outboxes.findAll().stream().filter(value -> value.getTripId().equals(trip.getId())
                && "process".equals(value.getMessageType()))).hasSize(1);
    }

    private OutboxReceipt receipt(TripCreationOutbox outbox) {
        return new OutboxReceipt(outbox.getTopic(), trip.getId().toString(), outbox.getPayload(), Instant.now());
    }

    private void convert(int number, boolean last) {
        var batch = transactions.prepareBatch(trip.getId(), owner.getUserId(), new InitialAttachmentUploadUrlRequest(
                number, 2, last, List.of(new InitialAttachmentUploadUrlRequest.Attachment("photo.jpg", "image/jpeg", 12L))));
        transactions.startProcessing(trip.getId(), owner.getUserId(), batch.uploadId());
        InitialAttachmentUploadBatchSnapshot claim = transactions.claimBatch().orElseThrow();
        for (var photo : claim.photos()) {
            var keys = transactions.startPhotoAttempt(claim, photo.itemId());
            transactions.savePhotoSuccess(claim, photo.itemId(), new DerivedPhotoKeys(
                    photo.objectKey(), keys.get(0), keys.get(1), null, null, null, null, null, 12L, 8L, 4L, null));
        }
        transactions.completeBatch(claim);
    }

    private TripCreationExecution execution() {
        return executions.findByTripId(trip.getId()).orElseThrow();
    }
}
