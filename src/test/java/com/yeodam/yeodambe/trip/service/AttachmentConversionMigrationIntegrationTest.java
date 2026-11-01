package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.SharedTestKafka;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadBatchSnapshot;
import com.yeodam.yeodambe.trip.service.response.AdditionalAttachmentUploadBatchSnapshot;
import com.yeodam.yeodambe.common.exception.AttachmentConversionUnavailableException;
import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutionException;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.stream.Stream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.awaitility.Awaitility;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.annotation.DirtiesContext;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "app.attachment-conversion.mode=HOLD",
        "app.attachment-conversion.operations.enabled=true",
        "app.attachment-conversion.operations.action=CHECK",
        "app.attachment-conversion.operations.kind=INITIAL",
        "app.attachment-conversion.operations.after-batch-id=0",
        "app.attachment-conversion.operations.limit=10"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class AttachmentConversionMigrationIntegrationTest {
    @Autowired private AttachmentConversionMigrationService migration;
    @Autowired private InitialAttachmentUploadTransactionService initial;
    @Autowired private AdditionalAttachmentUploadTransactionService additional;
    @Autowired private AttachmentConversionTaskRepository tasks;
    @Autowired private AttachmentConversionOutboxRepository outboxes;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AttachmentConversionAdmissionService admission;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationContext context;
    @Autowired private ObjectMapper json;
    @TempDir Path temporary;

    @ParameterizedTest
    @EnumSource(Kind.class)
    void partiallyConvertedBatchReusesSuccessAndAttempts(Kind kind) {
        var batch = fixture(kind, List.of("image/jpeg", "image/heic"));
        accept(batch);
        entityManager.flush();
        String items = kind == Kind.INITIAL ? "initial_attachment_upload_items" : "additional_attachment_upload_items";
        jdbc.update("UPDATE " + items + " SET conversion_attempts = 2 WHERE upload_batch_id = ?", batch.id());
        jdbc.update("UPDATE " + items + " SET derived_json = '{}', cleanup_json = '[]' WHERE upload_batch_id = ? AND file_order = 1", batch.id());
        entityManager.clear();
        assertThat(migration.transferPendingBatches(kind, 0, 10)).isEqualTo(1);
        var firstTaskIds = tasks.findAll().stream().map(AttachmentConversionTask::getId).toList();
        assertThat(firstTaskIds).hasSize(2);
        assertThat(migration.transferPendingBatches(kind, 0, 10)).isZero();
        assertThat(migration.returnBatchToDb(kind, batch.id())).isTrue();
        assertThat(migration.transferPendingBatches(kind, 0, 10)).isEqualTo(1);
        assertThat(tasks.findAll()).extracting(AttachmentConversionTask::getId)
                .containsExactlyInAnyOrderElementsOf(firstTaskIds);
        assertThat(outboxes.count()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT conversion_attempts FROM " + items + " WHERE upload_batch_id = ?",
                Integer.class, batch.id())).containsExactly(2, 2);
        assertThat(jdbc.queryForObject("SELECT derived_json FROM " + items + " WHERE upload_batch_id = ? AND file_order = 1",
                String.class, batch.id())).isEqualTo("{}");
        assertThat(jdbc.queryForObject("SELECT cleanup_json FROM " + items + " WHERE upload_batch_id = ? AND file_order = 1",
                String.class, batch.id())).isEqualTo("[]");
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void legacyLeaseAndLateCallbackCannotOverlapKafkaPhoto(Kind kind) {
        var batch = fixture(kind, List.of("image/jpeg"));
        accept(batch);
        entityManager.flush();
        jdbc.update("UPDATE " + table(batch) + " SET conversion_route = 'DB' WHERE upload_batch_id = ?", batch.id());
        entityManager.clear();
        Object source = kind == Kind.INITIAL ? initial : additional;
        Object original = ReflectionTestUtils.getField(source, "conversionProperties");
        var db = new AttachmentConversionProperties(
                AttachmentConversionRoute.DB, null, null, null, null);
        ReflectionTestUtils.setField(source, "conversionProperties", db);
        InitialAttachmentUploadBatchSnapshot initialClaim = null;
        AdditionalAttachmentUploadBatchSnapshot additionalClaim = null;
        try {
            if (kind == Kind.INITIAL) {
                initialClaim = initial.claimBatch(batch.tripId(), batch.userId(), batch.uploadId()).orElseThrow();
            } else {
                additionalClaim = additional.claimBatch().orElseThrow();
            }
        } finally {
            ReflectionTestUtils.setField(source, "conversionProperties", original);
        }
        entityManager.flush();
        assertThatThrownBy(() -> migration.transferPendingBatches(kind, 0, 10))
                .isInstanceOf(IllegalStateException.class);
        assertThat(route(batch)).isEqualTo("DB");
        assertThat(tasks.count()).isZero();
        String lease = kind == Kind.INITIAL ? "lease_until" : "lease_expires_at";
        jdbc.update("UPDATE " + table(batch) + " SET " + lease + " = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE upload_batch_id = ?", batch.id());
        entityManager.clear();
        assertThat(migration.transferPendingBatches(kind, 0, 10)).isEqualTo(1);
        if (kind == Kind.INITIAL) {
            assertThat(initial.renewLease(initialClaim)).isFalse();
        } else {
            assertThat(additional.renewLease(additionalClaim)).isFalse();
        }
        assertThat(route(batch)).isEqualTo("KAFKA");
        assertThat(tasks.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void unfinishedIoOrLiveClaimRejectsReturn(Kind kind) {
        var batch = fixture(kind, List.of("image/jpeg"));
        accept(batch);
        entityManager.flush();
        migration.transferPendingBatches(kind, 0, 10);
        var task = tasks.findAll().getFirst();
        task.claim("live", LocalDateTime.now().plusMinutes(10));
        entityManager.flush();
        assertThatThrownBy(() -> migration.returnBatchToDb(kind, batch.id()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(route(batch)).isEqualTo("KAFKA");
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void finalSweepOnlyTransfersRemainingSourceRoutes(Kind kind) {
        var first = fixture(kind, List.of("image/jpeg"));
        accept(first);
        entityManager.flush();
        migration.transferPendingBatches(kind, 0, 10);
        tasks.findAll().getFirst().claim("normal-kafka-work", LocalDateTime.now().plusMinutes(10));
        var remaining = fixture(kind, List.of("image/heic"));
        accept(remaining);
        entityManager.flush();
        assertThat(migration.transferPendingBatches(kind, 0, 10)).isEqualTo(1);
        assertThat(route(remaining)).isEqualTo("KAFKA");
    }

    @Test
    void checkIsReadOnlyAndReportsHistoricalMissingExecution() {
        var batch = fixture(Kind.ADDITIONAL, List.of("image/jpeg"));
        accept(batch);
        entityManager.flush();
        jdbc.update("DELETE FROM additional_attachment_executions WHERE trip_id = ?", batch.tripId());
        entityManager.clear();
        var page = migration.check(Kind.ADDITIONAL, 0, 10);
        assertThat(page).singleElement().satisfies(candidate -> {
            assertThat(candidate.missingExecution()).isTrue();
            assertThat(candidate.batchId()).isEqualTo(batch.id());
        });
        assertThat(route(batch)).isEqualTo("HOLD");
        assertThat(tasks.count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void holdAllowsExistingUploadContinuationButNoNewExecution(Kind kind) {
        var batch = fixture(kind, List.of("image/jpeg"));
        accept(batch);
        entityManager.flush();
        assertThat(route(batch)).isEqualTo("HOLD");
        assertThatThrownBy(admission::assertNewExecutionAllowed)
                .isInstanceOf(AttachmentConversionUnavailableException.class);
        jdbc.update("UPDATE " + table(batch) + " SET conversion_route = 'DB' WHERE upload_batch_id = ?", batch.id());
        entityManager.clear();
        if (kind == Kind.INITIAL) {
            assertThat(initial.claimBatch()).isEmpty();
            assertThat(initial.claimBatch(batch.tripId(), batch.userId(), batch.uploadId())).isEmpty();
        } else {
            assertThat(additional.claimBatch()).isEmpty();
            assertThatThrownBy(() -> additional.claimConversion(batch.tripId(), batch.userId(), batch.uploadId()))
                    .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        }
    }

    @Test
    void rollbackWaitsForPutAndPreservesJournal() {
        var batch = fixture(Kind.INITIAL, List.of("image/jpeg"));
        accept(batch);
        entityManager.flush();
        migration.transferPendingBatches(Kind.INITIAL, 0, 10);
        var task = tasks.findAll().getFirst();
        task.claim("expired-put", LocalDateTime.now().minusMinutes(1));
        task.startPhotoAttempt(1L, 2, "expired-put");
        entityManager.flush();
        assertThatThrownBy(() -> migration.returnBatchToDb(Kind.INITIAL, batch.id()))
                .isInstanceOf(IllegalStateException.class);
        task.clearPhotoAttempt();
        task.releaseClaim("expired-put");
        entityManager.flush();
        assertThat(migration.returnBatchToDb(Kind.INITIAL, batch.id())).isTrue();
        assertThat(migration.returnBatchToDb(Kind.INITIAL, batch.id())).isTrue();
        assertThat(outboxes.count()).isEqualTo(1);
        assertThat(tasks.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void transferRacingWithCompletionCreatesOneSetOfTasks(Kind kind) throws Exception {
        var transaction = new TransactionTemplate(transactionManager);
        var batch = transaction.execute(status -> fixture(kind, List.of("image/jpeg", "image/heic")));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var completion = executor.submit(() -> {
                start.await();
                accept(batch);
                return true;
            });
            var transfer = executor.submit(() -> {
                start.await();
                return migration.transferPendingBatches(kind, 0, 10);
            });
            start.countDown();
            completion.get(10, TimeUnit.SECONDS);
            transfer.get(10, TimeUnit.SECONDS);
        }
        migration.transferPendingBatches(kind, 0, 10);
        var firstTaskIds = tasks.findAll().stream().map(AttachmentConversionTask::getId).toList();
        migration.transferPendingBatches(kind, 0, 10);
        assertThat(firstTaskIds).hasSize(2);
        assertThat(tasks.findAll()).extracting(AttachmentConversionTask::getId)
                .containsExactlyInAnyOrderElementsOf(firstTaskIds);
        assertThat(outboxes.count()).isEqualTo(2);
        assertThat(route(batch)).isEqualTo("KAFKA");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void commandFailurePreservesPriorPagesAndOffsets() {
        var transaction = new TransactionTemplate(transactionManager);
        var first = transaction.execute(status -> {
            var batch = fixture(Kind.INITIAL, List.of("image/jpeg"));
            accept(batch);
            return batch;
        });
        var second = transaction.execute(status -> {
            var batch = fixture(Kind.INITIAL, List.of("image/jpeg"));
            accept(batch);
            jdbc.update("UPDATE initial_attachment_upload_batches SET lease_until = DATE_ADD(NOW(), INTERVAL 10 MINUTE) WHERE upload_batch_id = ?", batch.id());
            return batch;
        });
        assertThatThrownBy(() -> migration.transferPendingBatches(Kind.INITIAL, 0, 10))
                .isInstanceOf(IllegalStateException.class);
        assertThat(route(first)).isEqualTo("KAFKA");
        assertThat(route(second)).isEqualTo("HOLD");
        assertThat(outboxes.count()).isEqualTo(1);
        jdbc.update("UPDATE attachment_conversion_outbox SET record_partition = 0, record_offset = 17 WHERE trip_id = ?", first.tripId());
        assertThatThrownBy(() -> migration.transferPendingBatches(Kind.INITIAL, 0, 10))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT record_offset FROM attachment_conversion_outbox WHERE trip_id = ?",
                Long.class, first.tripId())).isEqualTo(17);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void evidenceExpiringWhileWaitingForTaskLockDoesNotMutateClaimOrRoute() throws Exception {
        var tx = new TransactionTemplate(transactionManager);
        var batch = tx.execute(status -> {
            var created = fixture(Kind.INITIAL, List.of("image/jpeg"));
            accept(created);
            return created;
        });
        migration.transferPendingBatches(Kind.INITIAL, 0, 10);
        migration.returnBatchToDb(Kind.INITIAL, batch.id());
        String taskId = tasks.findAll().getFirst().getId();
        jdbc.update("UPDATE attachment_conversion_tasks SET claim_token = 'expired-owner', lease_until = DATE_SUB(NOW(), INTERVAL 1 MINUTE), status = 'RUNNING' WHERE task_id = ?", taskId);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var locker = executor.submit(() -> tx.execute(status -> {
                jdbc.queryForObject("SELECT task_id FROM attachment_conversion_tasks WHERE task_id = ? FOR UPDATE", String.class, taskId);
                locked.countDown();
                try {
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return true;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Instant deadline = Instant.now().plusSeconds(2);
            var transfer = executor.submit(() -> migration.transferPendingBatches(Kind.INITIAL, 0, 10, deadline));
            try {
                Awaitility.await().atMost(Duration.ofSeconds(4)).until(() -> Instant.now().isAfter(deadline));
                assertThat(transfer.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            locker.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> transfer.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
        assertThat(route(batch)).isEqualTo("DB");
        assertThat(tasks.findById(taskId).orElseThrow().getClaimToken()).isEqualTo("expired-owner");
        assertThat(outboxes.count()).isEqualTo(1);
    }

    private Batch fixture(Kind kind, List<String> types) {
        var owner = new User(UUID.randomUUID() + "@yeodam.test", "접수");
        entityManager.persist(owner);
        var trip = new Trip(owner.getUserId(), "접수", LocalDate.now(), LocalDate.now());
        entityManager.persist(trip);
        String executionId = UUID.randomUUID().toString();
        String uploadId = UUID.randomUUID().toString();
        Long id;
        if (kind == Kind.INITIAL) {
            entityManager.persist(new TripCreationExecution(executionId, trip.getId(), owner.getUserId(), types.size()));
            var batch = new InitialAttachmentUploadBatch(uploadId, executionId, trip.getId(), owner.getUserId(),
                    1, types.size(), true);
            entityManager.persist(batch);
            for (int index = 0; index < types.size(); index++) {
                entityManager.persist(new InitialAttachmentUploadItem(batch, index + 1, "photo", types.get(index),
                        100L << index, "original/" + uploadId + "/" + index));
            }
            id = batch.getId();
        } else {
            jdbc.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", trip.getId());
            entityManager.refresh(trip);
            entityManager.persist(new AdditionalAttachmentExecution(executionId, trip.getId(), owner.getUserId(),
                    types.size(), LocalDateTime.now()));
            var batch = new AdditionalAttachmentUploadBatch(uploadId, executionId, trip.getId(), owner.getUserId(),
                    1, types.size(), true);
            entityManager.persist(batch);
            for (int index = 0; index < types.size(); index++) {
                entityManager.persist(new AdditionalAttachmentUploadItem(batch, index + 1, "photo", types.get(index),
                        100L << index, "original/" + uploadId + "/" + index));
            }
            id = batch.getId();
        }
        entityManager.flush();
        return new Batch(kind, id, trip.getId(), owner.getUserId(), uploadId);
    }

    private void accept(Batch batch) {
        if (batch.kind() == Kind.INITIAL) {
            initial.startProcessing(batch.tripId(), batch.userId(), batch.uploadId());
        } else {
            additional.markVerified(batch.tripId(), batch.userId(), batch.uploadId());
        }
    }

    private String route(Batch batch) {
        return jdbc.queryForObject("SELECT conversion_route FROM " + table(batch) + " WHERE upload_batch_id = ?",
                String.class, batch.id());
    }

    private LocalDateTime acceptedAt(Batch batch) {
        return jdbc.queryForObject("SELECT accepted_at FROM " + table(batch) + " WHERE upload_batch_id = ?",
                LocalDateTime.class, batch.id());
    }

    private String table(Batch batch) {
        return batch.kind() == Kind.INITIAL ? "initial_attachment_upload_batches" : "additional_attachment_upload_batches";
    }

    private record Batch(
            Kind kind,
            Long id,
            Long tripId,
            Long userId,
            String uploadId
    ) {
    }
}
