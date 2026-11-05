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
    @Autowired private AttachmentConversionOperationsRunner operations;
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
        AttachmentConversionProperties db = new AttachmentConversionProperties(
                AttachmentConversionRoute.DB,
                null,
                null,
                null,
                null,
                true,
                true,
                true
        );
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

    @Test
    void commandValidatesEvidenceBeforeMutationAndExpiresBetweenPages() throws Exception {
        var batch = fixture(Kind.INITIAL, List.of("image/jpeg"));
        accept(batch);
        entityManager.flush();
        Path evidence = temporary.resolve("drain.json");
        String valid = """
                {"target":"local-rehearsal","deploymentSha":"test-sha","action":"TRANSFER","kind":"INITIAL",
                 "afterBatchId":0,"limit":10,"batchId":null,"operator":"local-operator","observedAt":"%s",
                 "observation":"two local API logs and active I/O counters checked",
                 "instances":[{"id":"api-a","sha":"test-sha","mode":"HOLD","newStartsStopped":true,
                 "claimsStopped":true,"consumersStopped":true,"ioDrained":true}]}
                """.formatted(Instant.now());
        var args = new DefaultApplicationArguments(
                "--app.attachment-conversion.operations.action=TRANSFER",
                "--app.attachment-conversion.operations.kind=INITIAL",
                "--app.attachment-conversion.operations.after-batch-id=0",
                "--app.attachment-conversion.operations.limit=10",
                "--app.attachment-conversion.operations.target=local-rehearsal",
                "--app.attachment-conversion.operations.deployment-sha=test-sha",
                "--app.attachment-conversion.operations.instances=api-a",
                "--app.attachment-conversion.operations.drain-max-age=PT5M",
                "--app.attachment-conversion.operations.drain-evidence=" + evidence);
        for (String invalid : List.of(valid.replace("\"ioDrained\":true", "\"ioDrained\":false"),
                valid.replace("test-sha", "wrong-sha"), valid.replace("api-a", "api-b"),
                valid.replace("TRANSFER", "RETURN"), valid.replace("local-rehearsal", "other-target"),
                valid.replace("INITIAL", "ADDITIONAL"), valid.replace("\"afterBatchId\":0", "\"afterBatchId\":1"),
                valid.replace("\"limit\":10", "\"limit\":11"))) {
            Files.writeString(evidence, invalid);
            assertThat(operations.execute(args)).isNotZero();
            assertThat(tasks.count()).isZero();
            assertThat(route(batch)).isEqualTo("HOLD");
        }
        Files.writeString(evidence, valid);
        assertThat(operations.execute(args)).isZero();
        assertThat(tasks.count()).isEqualTo(1);
        assertThat(route(batch)).isEqualTo("KAFKA");
        assertThatThrownBy(() -> migration.returnBatchToDb(Kind.INITIAL, batch.id(), Instant.now().minusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(route(batch)).isEqualTo("KAFKA");
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
    @EnabledIfEnvironmentVariable(named = "OPERATIONS_REHEARSAL_JAR", matches = ".+")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void actualJarCommandsAndTwoApiInstancesPreserveSourceAndAiOrder() throws Exception {
        var tx = new TransactionTemplate(transactionManager);
        var batch = tx.execute(status -> {
            var created = fixture(Kind.INITIAL, List.of("image/jpeg", "image/heic"));
            accept(created);
            var items = entityManager.createQuery("select i from InitialAttachmentUploadItem i where i.batch.id = :id",
                    InitialAttachmentUploadItem.class).setParameter("id", created.id()).getResultList();
            for (var item : items) {
                var file = StoredFile.uploaded(created.userId(), "photo", item.getObjectKey(), item.getContentType());
                file.storageSize(item.getSizeBytes());
                entityManager.persist(file);
                var photo = TripAttachment.initial(created.tripId(), file.getId(), "saved/analyze/" + item.getId(), "saved/preview/" + item.getId());
                photo.storageSizes(20, 10, null);
                entityManager.persist(photo);
                item.linkAttachment(photo.getId());
                item.conversionCompleted(json.writeValueAsString(new DerivedPhotoKeys(item.getObjectKey(),
                        photo.getAnalyzeStorageKey(), photo.getPreviewStorageKey())));
            }
            return created;
        });
        Path logs = Path.of(System.getenv().getOrDefault("OPERATIONS_REHEARSAL_EVIDENCE", temporary.toString()));
        Files.createDirectories(logs);
        try (var admin = AdminClient.create(Map.of("bootstrap.servers",
                SharedTestKafka.bootstrapServers()))) {
            var topicNames = Stream.concat(
                    Stream.of("yeodam-image-initial-light", "yeodam-image-initial-heic",
                            "yeodam-image-additional-light", "yeodam-image-additional-heic", "yeodam-image-conversion-dlt"),
                    Stream.of("trip-create-request", "trip-create-result", "trip-update-request", "trip-update-result", "story-create-request", "story-create-result")
                            .map(name -> "rehearsal-" + name)).toList();
            for (String topic : topicNames) {
                try {
                    admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1)
                            .configs(Map.of("message.timestamp.type", "LogAppendTime")))).all().get(10, TimeUnit.SECONDS);
                } catch (ExecutionException exists) {
                    assertThat(exists.getCause()).isInstanceOf(TopicExistsException.class);
                }
            }
            var check = startJar(logs, "check", "HOLD", true, "CHECK");
            assertThat(check.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(check.exitValue()).isZero();
            var rejected = startJar(logs, "missing-evidence", "HOLD", true, "TRANSFER");
            assertThat(rejected.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(rejected.exitValue()).isNotZero();
            assertThat(route(batch)).isEqualTo("HOLD");
            Path evidence = logs.resolve("process-drain.json");
            Files.writeString(evidence, """
                    {"target":"local-rehearsal","deploymentSha":"local-jar","action":"TRANSFER","kind":"INITIAL",
                     "afterBatchId":0,"limit":10,"operator":"local-test","observedAt":"%s",
                     "observation":"Before launching the two API processes; no conversion process is running in this isolated database",
                     "instances":[{"id":"api-a","sha":"local-jar","mode":"HOLD","newStartsStopped":true,
                     "claimsStopped":true,"consumersStopped":true,"ioDrained":true},
                     {"id":"api-b","sha":"local-jar","mode":"HOLD","newStartsStopped":true,
                     "claimsStopped":true,"consumersStopped":true,"ioDrained":true}]}
                    """.formatted(Instant.now()));
            var transfer = startJar(logs, "transfer", "HOLD", true, "TRANSFER",
                    "--app.attachment-conversion.operations.target=local-rehearsal",
                    "--app.attachment-conversion.operations.deployment-sha=local-jar",
                    "--app.attachment-conversion.operations.instances=api-a,api-b",
                    "--app.attachment-conversion.operations.drain-max-age=PT5M",
                    "--app.attachment-conversion.operations.drain-evidence=" + evidence);
            assertThat(transfer.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(transfer.exitValue()).isZero();
            assertThat(route(batch)).isEqualTo("KAFKA");
            String transferEvidence = Files.readString(evidence);
            Files.writeString(evidence, transferEvidence.replace("\"action\":\"TRANSFER\"", "\"action\":\"RETURN\"")
                    .replace("\"limit\":10", "\"limit\":1,\"batchId\":" + batch.id()));
            var returned = startJar(logs, "return", "HOLD", true, "RETURN",
                    "--app.attachment-conversion.operations.batch-id=" + batch.id(),
                    "--app.attachment-conversion.operations.target=local-rehearsal",
                    "--app.attachment-conversion.operations.deployment-sha=local-jar",
                    "--app.attachment-conversion.operations.instances=api-a,api-b",
                    "--app.attachment-conversion.operations.drain-max-age=PT5M",
                    "--app.attachment-conversion.operations.drain-evidence=" + evidence);
            assertThat(returned.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(returned.exitValue()).isZero();
            assertThat(route(batch)).isEqualTo("DB");
            assertThat(migration.transferPendingBatches(Kind.INITIAL, 0, 10)).isEqualTo(1);
            assertThat(outboxes.count()).isEqualTo(2);
            Process first = startJar(logs, "api-a", "KAFKA", false, "CHECK");
            Process second = startJar(logs, "api-b", "KAFKA", false, "CHECK");
            try {
                var groups = List.of("initial-light", "initial-heic", "additional-light", "additional-heic").stream()
                        .map(suffix -> "rehearsal-image-" + suffix).toList();
                Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                    assertThat(first.isAlive() && second.isAlive()).isTrue();
                    var descriptions = admin.describeConsumerGroups(groups).all().get(5, TimeUnit.SECONDS);
                    assertThat(descriptions.values()).allSatisfy(group -> {
                        assertThat(group.members()).hasSize(2);
                        assertThat(group.members().stream().flatMap(member -> member.assignment().topicPartitions().stream()).toList()).hasSize(2);
                    });
                });
                Files.writeString(logs.resolve("group-assignment.txt"), admin.describeConsumerGroups(groups).all().get().toString());
                var producerConfig = new HashMap<String, Object>();
                producerConfig.put("bootstrap.servers", SharedTestKafka.bootstrapServers());
                producerConfig.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
                producerConfig.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
                try (var producer = new KafkaProducer<String, String>(producerConfig)) {
                    for (var outbox : outboxes.findAll()) {
                        for (int partition = 0; partition < 2; partition++) {
                            producer.send(new ProducerRecord<>(outbox.getTopic(), partition,
                                    batch.tripId().toString(), outbox.getPayload())).get(10, TimeUnit.SECONDS);
                        }
                    }
                    Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                            assertThat(tasks.findAll()).hasSize(2).allSatisfy(task ->
                                    assertThat(task.getStatus()).isEqualTo(AttachmentConversionTask.Status.SUCCEEDED)));
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_creation_outbox WHERE trip_id = ? AND message_type = 'photos_ready'",
                            Integer.class, batch.tripId())).isEqualTo(1);
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_creation_outbox WHERE trip_id = ? AND message_type = 'process'",
                            Integer.class, batch.tripId())).isZero();
                    var row = jdbc.queryForMap("SELECT outbox_id, payload FROM trip_creation_outbox WHERE trip_id = ? AND message_type = 'photos_ready'", batch.tripId());
                    var ready = new ProducerRecord<String, String>("rehearsal-trip-create-request", batch.tripId().toString(), row.get("payload").toString());
                    ready.headers().add("id", row.get("outbox_id").toString().getBytes(StandardCharsets.UTF_8));
                    ready.headers().add("outbox-source", "trip_creation_outbox".getBytes(StandardCharsets.UTF_8));
                    producer.send(ready).get(10, TimeUnit.SECONDS);
                    Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_creation_outbox WHERE trip_id = ? AND message_type = 'process'",
                                    Integer.class, batch.tripId())).isEqualTo(1));
                    Files.writeString(logs.resolve("source-ai-result.txt"), jdbc.queryForList(
                            "SELECT message_type, created_at FROM trip_creation_outbox WHERE trip_id = ? ORDER BY created_at", batch.tripId()).toString());
                    Files.writeString(evidence, transferEvidence.replace("TRANSFER", "FINAL_SWEEP").replace("HOLD", "KAFKA")
                            .replace("\"claimsStopped\":true", "\"claimsStopped\":false")
                            .replace("\"consumersStopped\":true", "\"consumersStopped\":false"));
                    var sweep = startJar(logs, "final-sweep", "KAFKA", true, "FINAL_SWEEP",
                            "--app.attachment-conversion.operations.target=local-rehearsal",
                            "--app.attachment-conversion.operations.deployment-sha=local-jar",
                            "--app.attachment-conversion.operations.instances=api-a,api-b",
                            "--app.attachment-conversion.operations.drain-max-age=PT5M",
                            "--app.attachment-conversion.operations.drain-evidence=" + evidence);
                    assertThat(sweep.waitFor(60, TimeUnit.SECONDS)).isTrue();
                    assertThat(sweep.exitValue()).isZero();
                    assertThat(outboxes.count()).isEqualTo(2);
                }
            } finally {
                for (Process process : List.of(first, second)) {
                    process.destroy();
                    if (!process.waitFor(15, TimeUnit.SECONDS)) {
                        process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                    }
                }
            }
        }
    }

    private Process startJar(Path logs, String name, String mode, boolean operationsEnabled, String action, String... extra) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m", "--enable-native-access=ALL-UNNAMED", "-jar", System.getenv("OPERATIONS_REHEARSAL_JAR"),
                "--spring.profiles.active=test", "--spring.config.import=", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.hikari.maximum-pool-size=3", "--spring.jpa.hibernate.ddl-auto=validate",
                "--app.attachment-conversion.mode=" + mode,
                "--app.attachment-conversion.operations.enabled=" + operationsEnabled,
                "--app.attachment-conversion.operations.action=" + action,
                "--app.attachment-conversion.operations.kind=INITIAL", "--app.attachment-conversion.operations.after-batch-id=0",
                "--app.attachment-conversion.operations.limit=10", "--app.trip.worker-initial-delay-ms=0",
                "--app.trip.result-listener-enabled=true", "--app.story.result-listener-enabled=true",
                "--app.outbox.receipt-listener-enabled=true", "--app.outbox.receipt-consumer-group=rehearsal-receipts",
                "--app.attachment-conversion.group-prefix=rehearsal-image",
                "--app.attachment-conversion.retry.technical-backoff=100ms",
                "--app.attachment-conversion.kafka.fetch-max-bytes=1048576",
                "--app.attachment-conversion.kafka.partition-fetch-max-bytes=524288",
                "--app.attachment-conversion.kafka.max-message-bytes=262144",
                "--app.attachment-conversion.admission.max-unfinished-photos.LIGHT=100",
                "--app.attachment-conversion.admission.max-unfinished-photos.HEIC=100",
                "--app.attachment-conversion.admission.max-unfinished-bytes.LIGHT=100000",
                "--app.attachment-conversion.admission.max-unfinished-bytes.HEIC=100000",
                "--app.attachment-conversion.admission.max-unfinished-age=1h",
                "--app.attachment-conversion.admission.snapshot-max-age=30s",
                "--app.attachment-conversion.admission.sample-interval=10s",
                "--app.attachment-conversion.admission.reopen-ratio=0.5",
                "--app.attachment-conversion.recovery.interval=5m", "--app.attachment-conversion.recovery.page-size=10",
                "--app.attachment-conversion.recovery.max-pages-per-run=2", "--app.attachment-conversion.recovery.max-reissues-per-run=10"));
        for (String topic : List.of("trip-create-request", "trip-create-result", "trip-update-request", "trip-update-result", "story-create-request", "story-create-result")) {
            command.add("--app.kafka.topics." + topic + "=rehearsal-" + topic);
        }
        command.addAll(List.of(extra));
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(logs.resolve(name + ".log").toFile());
        var database = context.getBean(JdbcConnectionDetails.class);
        process.environment().put("SPRING_DATASOURCE_URL", database.getJdbcUrl());
        process.environment().put("SPRING_DATASOURCE_USERNAME", database.getUsername());
        process.environment().put("SPRING_DATASOURCE_PASSWORD", database.getPassword());
        var redis = context.getBean(DataRedisConnectionDetails.class).getStandalone();
        process.environment().put("SPRING_DATA_REDIS_HOST", redis.getHost());
        process.environment().put("SPRING_DATA_REDIS_PORT", Integer.toString(redis.getPort()));
        process.environment().put("SPRING_DATA_REDIS_DATABASE", Integer.toString(redis.getDatabase()));
        process.environment().put("SPRING_KAFKA_BOOTSTRAP_SERVERS", SharedTestKafka.bootstrapServers());
        return process.start();
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
