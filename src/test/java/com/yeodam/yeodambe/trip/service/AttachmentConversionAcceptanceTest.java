package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadCompleteRequest;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.application.name=conversion-acceptance-test",
        "app.attachment-conversion.mode=KAFKA",
        "app.attachment-conversion.retry.technical-backoff=100ms",
        "app.attachment-conversion.kafka.fetch-max-bytes=1048576",
        "app.attachment-conversion.kafka.partition-fetch-max-bytes=524288",
        "app.attachment-conversion.kafka.max-message-bytes=262144",
        "spring.kafka.listener.auto-startup=false",
        "app.attachment-conversion.admission.max-unfinished-photos.LIGHT=100",
        "app.attachment-conversion.admission.max-unfinished-photos.HEIC=100",
        "app.attachment-conversion.admission.max-unfinished-bytes.LIGHT=100000",
        "app.attachment-conversion.admission.max-unfinished-bytes.HEIC=100000",
        "app.attachment-conversion.admission.max-unfinished-age=1h",
        "app.attachment-conversion.admission.snapshot-max-age=30s",
        "app.attachment-conversion.admission.sample-interval=10s",
        "app.attachment-conversion.admission.reopen-ratio=0.5"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class AttachmentConversionAcceptanceTest {
    @Autowired private InitialAttachmentUploadTransactionService initial;
    @Autowired private AdditionalAttachmentUploadTransactionService additional;
    @Autowired private AdditionalAttachmentUploadCompletionService additionalCompletion;
    @Autowired private AttachmentConversionTaskRepository tasks;
    @Autowired private AttachmentConversionOutboxRepository outboxes;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper json;

    @ParameterizedTest
    @EnumSource(Kind.class)
    void mixedBatchCreatesTwoTasksAndTwoOutboxesAtomically(Kind kind) {
        var batch = fixture(kind, List.of("image/jpeg", "image/png", "image/heic"));

        accept(batch);
        entityManager.flush();
        entityManager.clear();

        assertThat(tasks.findAll()).extracting(AttachmentConversionTask::getLane)
                .containsExactlyInAnyOrder(Lane.LIGHT, Lane.HEIC);
        assertThat(tasks.findAll()).allSatisfy(task -> {
            assertThat(task.getKind()).isEqualTo(kind);
            assertThat(task.getTripId()).isEqualTo(batch.tripId());
            assertThat(task.getTotalPhotos()).isEqualTo(task.getLane() == Lane.LIGHT ? 2 : 1);
            assertThat(task.getTotalBytes()).isEqualTo(task.getLane() == Lane.LIGHT ? 300 : 400);
            assertThat(task.getRemainingPhotos()).isEqualTo(task.getTotalPhotos());
            assertThat(task.getRemainingBytes()).isEqualTo(task.getTotalBytes());
        });
        assertThat(outboxes.findAll()).hasSize(2).allSatisfy(outbox -> {
            var task = tasks.findById(outbox.getTaskId()).orElseThrow();
            String expectedTopic = "yeodam-image-" + kind.name().toLowerCase()
                    + (task.getLane() == Lane.LIGHT ? "-light" : "-heic");
            assertThat(outbox.getTopic()).isEqualTo(expectedTopic);
            assertThat(outbox.getTripId()).isEqualTo(batch.tripId());
            assertThat(outbox.getDeliveryNo()).isEqualTo(1);
            assertThat(outbox.getRequestId()).isNotBlank();
            assertThat(outbox.getStatus()).isEqualTo(AttachmentConversionOutbox.Status.PENDING);
            assertThat(outbox.isCdcManaged()).isTrue();
            assertThat(outbox.getEventSource()).isEqualTo("attachment_conversion_outbox");
            assertThat(json.readTree(outbox.getPayload()).properties()).hasSize(2);
            assertThat(json.readTree(outbox.getPayload()).path("taskId").asString()).isEqualTo(task.getId());
            assertThat(json.readTree(outbox.getPayload()).path("schemaVersion").asInt()).isEqualTo(1);
        });
        assertThat(route(batch)).isEqualTo("KAFKA");
        if (kind == Kind.INITIAL) {
            assertThat(acceptedAt(batch)).isNotNull();
        }
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void singleFormatDoesNotCreateEmptyTask(Kind kind) {
        var batch = fixture(kind, List.of("image/heic"));
        accept(batch);
        assertThat(tasks.findAll()).singleElement().satisfies(task -> {
            assertThat(task.getLane()).isEqualTo(Lane.HEIC);
            assertThat(task.getTotalPhotos()).isEqualTo(1);
        });
        assertThat(outboxes.findAll()).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void duplicateAcceptanceDoesNotEnqueueAgain(Kind kind) {
        var batch = fixture(kind, List.of("image/jpeg", "image/heic"));
        accept(batch);
        entityManager.flush();
        var accepted = kind == Kind.INITIAL ? acceptedAt(batch) : null;
        setMode(AttachmentConversionRoute.DB);
        accept(batch);
        entityManager.flush();
        assertThat(route(batch)).isEqualTo("KAFKA");
        if (kind == Kind.INITIAL) {
            assertThat(acceptedAt(batch)).isEqualTo(accepted);
        }
        assertThat(tasks.count()).isEqualTo(2);
        assertThat(outboxes.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void holdAcceptanceDoesNotEnqueueOrChangeRouteOnReacceptance(Kind kind) {
        var batch = fixture(kind, List.of("image/jpeg"));
        setMode(AttachmentConversionRoute.HOLD);
        accept(batch);
        setMode(AttachmentConversionRoute.KAFKA);
        accept(batch);
        entityManager.flush();
        assertThat(route(batch)).isEqualTo("HOLD");
        if (kind == Kind.INITIAL) {
            assertThat(acceptedAt(batch)).isNotNull();
        }
        assertThat(tasks.count()).isZero();
        assertThat(outboxes.count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void heldOrKafkaBatchIsNotClaimedByLegacyWorker(Kind kind) {
        var batch = fixture(kind, List.of("image/jpeg"));
        setMode(AttachmentConversionRoute.DB);
        accept(batch);
        entityManager.flush();
        for (String route : List.of("HOLD", "KAFKA")) {
            jdbc.update("UPDATE " + table(batch) + " SET conversion_route = ? WHERE upload_batch_id = ?",
                    route, batch.id());
            entityManager.clear();
            if (kind == Kind.INITIAL) {
                assertThat(initial.claimBatch()).isEmpty();
                assertThat(initial.claimBatch(batch.tripId(), batch.userId(), batch.uploadId())).isEmpty();
            } else {
                assertThat(additional.claimBatch()).isEmpty();
                assertThatThrownBy(() -> additional.claimConversion(batch.tripId(), batch.userId(), batch.uploadId()))
                        .isInstanceOf(TripAttachmentAddNotAllowedException.class);
                var request = new AdditionalAttachmentUploadCompleteRequest(batch.uploadId());
                assertThatThrownBy(() -> additionalCompletion.prepareBatchPhotos(batch.tripId(), batch.userId(), request))
                        .isInstanceOf(TripAttachmentAddNotAllowedException.class);
                assertThatThrownBy(() -> additionalCompletion.prepareQueueBatch(batch.tripId(), batch.userId(), request))
                        .isInstanceOf(TripAttachmentAddNotAllowedException.class);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void dbAcceptanceKeepsLegacyClaimAvailable(Kind kind) {
        var batch = fixture(kind, List.of("image/jpeg"));
        setMode(AttachmentConversionRoute.DB);
        accept(batch);
        entityManager.flush();
        assertThat(route(batch)).isEqualTo("DB");
        assertThat(tasks.count()).isZero();
        assertThat(outboxes.count()).isZero();
        if (kind == Kind.INITIAL) {
            assertThat(initial.claimBatch(batch.tripId(), batch.userId(), batch.uploadId())).isPresent();
        } else {
            assertThat(additional.claimBatch()).isPresent();
        }
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollbackLeavesNeitherTaskNorOutbox(Kind kind) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var batch = fixture(kind, List.of("image/jpeg", "image/heic"));
            accept(batch);
            entityManager.flush();
            assertThat(tasks.count()).isEqualTo(2);
            assertThat(outboxes.count()).isEqualTo(2);
            status.setRollbackOnly();
        });
        assertThat(tasks.count()).isZero();
        assertThat(outboxes.count()).isZero();
    }

    @Test
    void dbLegacyBatchWithoutExecutionCanStillBeAccepted() {
        var batch = fixture(Kind.ADDITIONAL, List.of("image/jpeg"));
        jdbc.update("DELETE FROM additional_attachment_executions WHERE trip_id = ?", batch.tripId());
        entityManager.clear();
        setMode(AttachmentConversionRoute.DB);

        accept(batch);
        accept(batch);
        entityManager.flush();

        assertThat(route(batch)).isEqualTo("DB");
        assertThat(jdbc.queryForObject("SELECT status FROM additional_attachment_upload_batches WHERE upload_batch_id = ?",
                String.class, batch.id())).isEqualTo("VERIFIED");
        assertThat(tasks.count()).isZero();
        assertThat(outboxes.count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = AttachmentConversionRoute.class, names = {"HOLD", "KAFKA"})
    void nonDbAcceptanceRequiresMatchingActiveExecution(AttachmentConversionRoute mode) {
        var batch = fixture(Kind.ADDITIONAL, List.of("image/jpeg"));
        jdbc.update("DELETE FROM additional_attachment_executions WHERE trip_id = ?", batch.tripId());
        entityManager.clear();
        setMode(mode);

        assertThatThrownBy(() -> accept(batch)).isInstanceOf(TripAttachmentAddNotAllowedException.class);
        assertThat(route(batch)).isEqualTo("DB");
        assertThat(tasks.count()).isZero();
        assertThat(outboxes.count()).isZero();
    }

    @AfterEach
    void restoreMode() {
        setMode(AttachmentConversionRoute.KAFKA);
    }

    private void setMode(AttachmentConversionRoute mode) {
        var configured = new AttachmentConversionProperties(mode,
                new AttachmentConversionProperties.Admission(Map.of(Lane.LIGHT, 100L, Lane.HEIC, 100L),
                        Map.of(Lane.LIGHT, 100000L, Lane.HEIC, 100000L), Duration.ofHours(1),
                        Duration.ofSeconds(30), Duration.ofSeconds(10), 0.5),
                new AttachmentConversionProperties.Operations(false), null);
        ReflectionTestUtils.setField(initial, "conversionProperties", configured);
        ReflectionTestUtils.setField(additional, "conversionProperties", configured);
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
