package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.SharedTestKafka;
import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.client.AttachmentConversionKafkaListener;
import com.yeodam.yeodambe.trip.config.AttachmentConversionKafkaConfiguration;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties.Topics;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.request.AttachmentConversionSeed;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.MethodKafkaListenerEndpoint;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AttachmentConversionKafkaIntegrationTest {
    private static final Duration BACKOFF = Duration.ofMillis(100);
    @Autowired private KafkaTemplate<String, String> kafka;
    @Autowired private ConcurrentKafkaListenerContainerFactoryConfigurer configurer;
    @Autowired private AttachmentConversionTransactionService database;
    @Autowired private InitialAttachmentUploadTransactionService source;
    @Autowired private AttachmentConversionEnqueueService enqueue;
    @Autowired private AttachmentConversionTaskRepository tasks;
    @Autowired private InitialAttachmentUploadBatchRepository batches;
    @Autowired private InitialAttachmentUploadItemRepository items;
    @Autowired private TripCreationExecutionRepository executions;
    @Autowired private TripCreationOutboxRepository outboxes;
    @Autowired private TripRepository trips;
    @Autowired private UserRepository users;
    @Autowired private UserStatsRepository stats;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @TempDir private Path temporary;

    @Test
    void commitWaitsForTaskDatabaseAndCleanup() throws Exception {
        var db = mock(AttachmentConversionTransactionService.class, org.mockito.AdditionalAnswers.delegatesTo(database));
        var photos = mock(TripAttachmentDerivativeService.class);
        var cleanup = new CompletableFuture<DerivedPhotoKeys>();
        var planned = new AtomicReference<DerivedPhotoKeys>();
        var attempted = new CountDownLatch(1);
        var saving = new AtomicBoolean(false);
        var databaseReady = new AtomicBoolean(false);
        when(photos.processConversionPhoto(any(), any(), any(), any())).thenAnswer(call -> {
            planned.set(((com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim.Attempt) call.getArgument(2)).plannedKeys());
            return cleanup;
        });
        doAnswer(call -> {
            attempted.countDown();
            if (!databaseReady.get()) {
                throw new TransientDataAccessResourceException("DB delayed after storage cleanup");
            }
            saving.set(true);
            return database.completePhoto(call.getArgument(0), call.getArgument(1), call.getArgument(2));
        }).when(db).completePhoto(any(), anyLong(), any());
        try (var h = new Harness(mockWorker(), kafka)) {
            var worker = new AttachmentConversionWorker(db, photos, h.topics, BACKOFF);
            h.use(worker);
            var task = accepted(1);
            var first = h.send(h.topics.initialLight(), message(task));
            var second = h.send(h.topics.initialLight(), message(UUID.randomUUID().toString()));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    verify(photos).processConversionPhoto(any(), any(), any(), any()));
            h.uncommitted(first);
            assertThat(saving).isFalse();
            cleanup.complete(output(planned.get()));
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            h.uncommitted(first);
            assertThat(tasks.findById(task).orElseThrow().getStatus()).isEqualTo(AttachmentConversionTask.Status.RUNNING);
            verify(db, times(1)).claim(eq(task), any(), any());
            databaseReady.set(true);
            h.committed(first);
            assertThat(tasks.findById(task).orElseThrow().getStatus()).isEqualTo(AttachmentConversionTask.Status.SUCCEEDED);
            assertThat(saving).isTrue();
            h.committed(second); // unknown DB task is malformed only after the first task's actual commit
        }
    }

    @Test
    void commitFailureKeepsTopicPausedUntilOriginalCommitConfirmed() throws Exception {
        var worker = mockWorker();
        var seen = new LinkedBlockingQueue<String>();
        when(worker.process(anyString(), any(), any(), anyString(), anyInt())).thenAnswer(call -> {
            seen.add(call.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });
        try (var h = new Harness(worker, kafka)) {
            h.commitFailure.set(true);
            h.start();
            String firstId = UUID.randomUUID().toString();
            String secondId = UUID.randomUUID().toString();
            var first = h.send(h.topics.initialLight(), message(firstId));
            var second = h.send(h.topics.initialLight(), message(secondId));
            assertThat(seen.poll(15, TimeUnit.SECONDS)).isEqualTo(firstId);
            await().atMost(Duration.ofSeconds(5)).until(() -> h.failedCommits.get() >= 3);
            h.uncommitted(first);
            assertThat(seen).isEmpty();
            assertThat(h.consumerThreadViolations).hasValue(0);
            h.commitFailure.set(false);
            h.committed(first);
            assertThat(seen.poll(5, TimeUnit.SECONDS)).isEqualTo(secondId);
            h.committed(second);
            assertThat(h.failedCommits.get()).isLessThan(30);
        }
    }

    @Test
    void heicDelayDoesNotPauseLightContainer() throws Exception {
        var worker = mockWorker();
        var blocked = new CompletableFuture<Void>();
        var lightCompleted = new CountDownLatch(1);
        when(worker.process(anyString(), any(), any(), anyString(), anyInt())).thenAnswer(call -> {
            if (call.getArgument(2) == Lane.HEIC) {
                return blocked;
            }
            lightCompleted.countDown();
            return CompletableFuture.completedFuture(null);
        });
        try (var h = new Harness(worker, kafka)) {
            h.start();
            var heic = h.send(h.topics.initialHeic(), message(UUID.randomUUID().toString()));
            var light = h.send(h.topics.initialLight(), message(UUID.randomUUID().toString()));
            assertThat(lightCompleted.await(5, TimeUnit.SECONDS)).isTrue();
            h.committed(light);
            h.uncommitted(heic);
            blocked.complete(null);
            h.committed(heic);
        }
    }

    @Test
    void blockedHeicDltSendDoesNotBlockLightWorkOrAnotherDltCallback() throws Exception {
        var sending = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var dlt = mock(KafkaTemplate.class);
        var lightCompleted = new CountDownLatch(1);
        var worker = mockWorker();
        when(worker.process(anyString(), any(), any(), anyString(), anyInt())).thenAnswer(call -> {
            lightCompleted.countDown();
            return CompletableFuture.completedFuture(null);
        });
        try (var h = new Harness(worker, dlt)) {
            when(dlt.send(any(ProducerRecord.class))).thenAnswer(call -> {
                ProducerRecord<String, String> record = call.getArgument(0);
                String origin = new String(record.headers().lastHeader("conversion-original-topic").value(), java.nio.charset.StandardCharsets.UTF_8);
                if (origin.equals(h.topics.initialHeic())) {
                    sending.countDown();
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                }
                return kafka.send(record);
            });
            h.start();
            var heic = h.send(h.topics.initialHeic(), "{");
            try {
                assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
                var malformedLight = h.send(h.topics.initialLight(), "{");
                var light = h.send(h.topics.additionalLight(), message(UUID.randomUUID().toString()));
                assertThat(lightCompleted.await(5, TimeUnit.SECONDS)).isTrue();
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(h.offset(malformedLight)).isEqualTo(malformedLight.offset() + 1));
                h.committed(light);
                h.uncommitted(heic);
            } finally {
                release.countDown();
            }
            h.committed(heic);
        }
    }

    @Test
    void dltFailureAndDatabaseFailureLeaveOriginalUncommitted() throws Exception {
        var worker = mockWorker();
        var attempts = new AtomicInteger();
        when(worker.process(anyString(), any(), any(), anyString(), anyInt())).thenAnswer(call -> {
            attempts.incrementAndGet();
            return CompletableFuture.failedFuture(new ClassCastException("private business input"));
        });
        var dlt = mock(KafkaTemplate.class);
        var dltAttempts = new AtomicInteger();
        when(dlt.send(any(ProducerRecord.class))).thenAnswer(call -> {
            dltAttempts.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("https://private?token=hidden"));
        });
        try (var h = new Harness(worker, dlt)) {
            h.start();
            var valid = h.send(h.topics.initialLight(), message(UUID.randomUUID().toString()));
            var malformed = h.send(h.topics.initialHeic(), "{");
            await().atMost(Duration.ofSeconds(15)).until(() -> attempts.get() >= 4 && dltAttempts.get() >= 4);
            h.uncommitted(valid);
            h.uncommitted(malformed);
            verify(dlt, never()).send(argThat((ProducerRecord<String, String> record) -> valid.value().equals(record.value())));
        }
    }

    @Test
    void malformedMessageIsAckedOnlyAfterDltSuccess() throws Exception {
        var dlt = mock(KafkaTemplate.class);
        var send = new CompletableFuture<SendResult<String, String>>();
        var captured = new AtomicReference<ProducerRecord<String, String>>();
        when(dlt.send(any(ProducerRecord.class))).thenAnswer(call -> {
            captured.set(call.getArgument(0));
            return send;
        });
        try (var h = new Harness(mockWorker(), dlt)) {
            h.start();
            var malformed = h.send(h.topics.initialLight(), "{");
            await().atMost(Duration.ofSeconds(15)).until(() -> captured.get() != null);
            h.uncommitted(malformed);
            var result = kafka.send(captured.get()).get(5, TimeUnit.SECONDS);
            send.complete(result);
            h.committed(malformed);
            try (var consumer = dltConsumer(h)) {
                var records = consumer.poll(Duration.ofSeconds(5));
                assertThat(records).hasSize(1);
                assertThat(records.iterator().next().value()).isEqualTo("{");
            }
        }
    }

    @Test
    void synchronousFatalFailureAndCancelledFutureLeaveOffsetPendingWithoutUnsafeLogs() throws Exception {
        var worker = mockWorker();
        var cancelled = new CompletableFuture<Void>();
        cancelled.cancel(true);
        var ready = new AtomicBoolean(false);
        when(worker.process(anyString(), any(), any(), anyString(), anyInt())).thenAnswer(call ->
                ready.get() ? CompletableFuture.completedFuture(null) : cancelled);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logger.addAppender(logs);
        try (var h = new Harness(worker, kafka)) {
            h.synchronousFailure.set(true);
            h.start();
            var record = h.send(h.topics.initialLight(), message(UUID.randomUUID().toString()));
            await().atMost(Duration.ofSeconds(5)).until(() -> h.synchronousAttempts.get() >= 3);
            h.uncommitted(record);
            verify(worker, never()).process(anyString(), any(), any(), anyString(), anyInt());
            h.synchronousFailure.set(false);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    verify(worker, atLeast(3)).process(anyString(), any(), any(), anyString(), anyInt()));
            h.uncommitted(record);
            ready.set(true);
            h.committed(record);
            assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain("task9-private", "?secret=", record.value()));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void receiptStartsFromExistingRecordAndRetriesWithoutWorkerOrEarlyCommit() throws Exception {
        try (var h = new Harness(mockWorker(), kafka)) {
            var record = h.send(h.topics.initialLight(), "receipt-before-start");
            var received = new ReceiptProbe();
            var consumers = new DefaultKafkaConsumerFactory<Object, Object>(Map.of("bootstrap.servers", SharedTestKafka.bootstrapServers(),
                    "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class,
                    "auto.offset.reset", "latest", "enable.auto.commit", true));
            var configuration = new AttachmentConversionKafkaConfiguration(BACKOFF, 0, 0, 0);
            var factory = configuration.attachmentConversionReceiptKafkaListenerContainerFactory(configurer, consumers, h.scheduler, h.registry);
            factory.getContainerProperties().setPollTimeout(100);
            factory.getContainerProperties().setPollTimeoutWhilePaused(Duration.ofMillis(100));
            var endpoint = new MethodKafkaListenerEndpoint<Object, Object>();
            endpoint.setId(h.prefix + "-initialLight");
            endpoint.setGroupId(h.prefix + "-initialLight");
            endpoint.setTopics(h.topics.initialLight());
            endpoint.setBean(received);
            endpoint.setMethod(ReceiptProbe.class.getMethod("consume", ConsumerRecord.class));
            var handlers = new DefaultMessageHandlerMethodFactory();
            handlers.afterPropertiesSet();
            endpoint.setMessageHandlerMethodFactory(handlers);
            h.registry.registerListenerContainer(endpoint, factory, false);
            h.registry.start();
            await().atMost(Duration.ofSeconds(15)).until(() -> received.attempts.get() >= 3);
            h.uncommitted(record);
            received.ready.set(true);
            h.committed(record);
        }
    }

    public static final class ReceiptProbe {
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicBoolean ready = new AtomicBoolean();

        public void consume(ConsumerRecord<String, String> record) {
            attempts.incrementAndGet();
            if (!ready.get()) {
                throw new ClassCastException("task9-private?secret=receipt");
            }
        }
    }

    @Test
    void partitionLostNeverAcknowledgesUnfinishedTask() throws Exception {
        var worker = mockWorker();
        var unfinished = new CompletableFuture<Void>();
        var started = new CountDownLatch(1);
        when(worker.process(anyString(), any(), any(), anyString(), anyInt())).thenAnswer(call -> {
            started.countDown();
            return unfinished;
        });
        try (var h = new Harness(worker, kafka)) {
            h.start();
            var record = h.send(h.topics.initialLight(), message(UUID.randomUUID().toString()));
            assertThat(started.await(15, TimeUnit.SECONDS)).isTrue();
            h.losePartitions.set(true);
            await().atMost(Duration.ofSeconds(5)).until(() -> h.lostPartitions.get());
            unfinished.complete(null);
            h.uncommitted(record);
            verify(worker, atLeastOnce()).revoke(record.topic(), Set.of(record.partition()));
            assertThat(h.consumerThreadViolations).hasValue(0);
        }
    }

    @Test
    void thirdTransientPhotoFailureCommitsFailedExecutionBeforeAck() throws Exception {
        var photos = mock(TripAttachmentDerivativeService.class);
        when(photos.processConversionPhoto(any(), any(), any(), any())).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("temporary native failure")));
        when(photos.isTransientConversionFailure(any())).thenReturn(true);
        var db = mock(AttachmentConversionTransactionService.class, org.mockito.AdditionalAnswers.delegatesTo(database));
        doAnswer(call -> {
            var result = database.recordPhotoFailure(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            if (result.status() == com.yeodam.yeodambe.trip.service.response.AttachmentConversionStepResult.Status.RETRY_LATER) {
                var claim = (com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim) call.getArgument(0);
                jdbc.update("UPDATE initial_attachment_upload_items SET conversion_next_attempt_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE upload_item_id = ?",
                        claim.photos().getFirst().itemId());
                jdbc.update("UPDATE attachment_conversion_tasks SET next_attempt_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE task_id = ?", claim.taskId());
            }
            return result;
        }).when(db).recordPhotoFailure(any(), anyLong(), anyBoolean());
        try (var h = new Harness(mockWorker(), kafka)) {
            var worker = new AttachmentConversionWorker(db, photos, h.topics, BACKOFF);
            h.use(worker);
            var taskId = accepted(1);
            var record = h.send(h.topics.initialLight(), message(taskId));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(AttachmentConversionTask.Status.FAILED));
            h.committed(record);
            var task = tasks.findById(taskId).orElseThrow();
            assertThat(executions.findById(batches.findById(task.getInitialBatchId()).orElseThrow().getExecutionId())
                    .orElseThrow().getStatus()).isEqualTo(TripCreationExecution.Status.FAILED);
            assertThat(items.findAll().stream().filter(item -> item.getBatch().getId().equals(task.getInitialBatchId())))
                    .allSatisfy(item -> assertThat(item.getConversionAttempts()).isEqualTo(3));
            verify(photos, times(3)).processConversionPhoto(any(), any(), any(), any());
        }
    }

    @Test
    void rebalanceFinishesCurrentPhotoAndResumesWithoutDuplicateAccounting() throws Exception {
        var photos = mock(TripAttachmentDerivativeService.class);
        var current = new CompletableFuture<DerivedPhotoKeys>();
        var firstOutput = new AtomicReference<DerivedPhotoKeys>();
        var started = new LinkedBlockingQueue<Long>();
        when(photos.processConversionPhoto(any(), any(), any(), any())).thenAnswer(call -> {
            var attempt = (com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim.Attempt) call.getArgument(2);
            firstOutput.compareAndSet(null, attempt.plannedKeys());
            started.add(attempt.itemId());
            return started.size() == 1 ? current : CompletableFuture.completedFuture(output(attempt.plannedKeys()));
        });
        try (var h = new Harness(mockWorker(), kafka)) {
            var worker = spy(new AttachmentConversionWorker(database, photos, h.topics, BACKOFF));
            h.use(worker);
            var taskId = accepted(2);
            var record = h.send(h.topics.initialLight(), message(taskId));
            await().atMost(Duration.ofSeconds(15)).until(() -> started.size() == 1);
            h.initialLight().enforceRebalance();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> verify(worker).revoke(record.topic(), Set.of(0)));
            await().atMost(Duration.ofSeconds(10)).until(() -> h.initialLight().isPauseRequested());
            h.uncommitted(record);
            verify(worker, times(1)).process(eq(taskId), any(), any(), anyString(), anyInt());
            current.complete(output(firstOutput.get()));
            h.committed(record);
            verify(photos, times(2)).processConversionPhoto(any(), any(), any(), any());
            var task = tasks.findById(taskId).orElseThrow();
            assertThat(task.getStatus()).isEqualTo(AttachmentConversionTask.Status.SUCCEEDED);
            assertThat(items.findAll().stream().filter(item -> item.getBatch().getId().equals(task.getInitialBatchId())))
                    .allSatisfy(item -> assertThat(item.getConversionAttempts()).isEqualTo(1));
            assertThat(outboxes.findAll().stream().filter(outbox -> outbox.getTripId().equals(task.getTripId())))
                    .hasSize(1);
        }
    }

    @Test
    void lostDatabaseTokenCannotApplyOldOutput() throws Exception {
        var photos = mock(TripAttachmentDerivativeService.class);
        var current = new CompletableFuture<DerivedPhotoKeys>();
        var original = new AtomicReference<com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim>();
        var planned = new AtomicReference<DerivedPhotoKeys>();
        when(photos.processConversionPhoto(any(), any(), any(), any())).thenAnswer(call -> {
            original.set(call.getArgument(0));
            planned.set(((com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim.Attempt) call.getArgument(2)).plannedKeys());
            return current;
        });
        try (var h = new Harness(mockWorker(), kafka)) {
            var worker = spy(new AttachmentConversionWorker(database, photos, h.topics, BACKOFF));
            h.use(worker);
            var taskId = accepted(1);
            var record = h.send(h.topics.initialLight(), message(taskId));
            await().atMost(Duration.ofSeconds(15)).until(() -> original.get() != null);
            h.initialLight().enforceRebalance();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> verify(worker).revoke(record.topic(), Set.of(0)));
            jdbc.update("UPDATE attachment_conversion_tasks SET lease_until = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE task_id = ?", taskId);
            var successor = database.claim(taskId, Kind.INITIAL, Lane.LIGHT).claim();
            assertThat(successor.claimToken()).isNotEqualTo(original.get().claimToken());
            current.complete(output(planned.get()));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                var item = items.findById(original.get().photos().getFirst().itemId()).orElseThrow();
                assertThat(item.getCleanupStartedAt()).isNotNull();
                assertThat(item.getDerivedJson()).isNull();
                assertThat(item.getConversionAttempts()).isEqualTo(1);
            });
            assertThat(tasks.findById(taskId).orElseThrow().getClaimToken()).isEqualTo(successor.claimToken());
            h.uncommitted(record);
        }
    }

    @Test
    void delayedDatabaseAndDltFailureKeepOffsetAcrossRestart() throws Exception {
        try (var h = new Harness(mockWorker(), kafka)) {
            var valid = h.send(h.topics.initialLight(), message(UUID.randomUUID().toString()));
            var malformed = h.send(h.topics.initialHeic(), "{");
            Process first = probe(h, "failing");
            try {
                await().ignoreExceptions().atMost(Duration.ofSeconds(30)).until(() ->
                        Integer.parseInt(Files.readString(temporary.resolve("db-failing"))) >= 3
                                && Integer.parseInt(Files.readString(temporary.resolve("dlt-failing"))) >= 3);
                h.uncommitted(valid);
                h.uncommitted(malformed);
                first.destroyForcibly();
                assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
                assertThat(first.exitValue()).isNotZero();
                System.out.println("Task9 forced JVM exit pid=" + first.pid() + " exit=" + first.exitValue());
                h.uncommitted(valid);
                h.uncommitted(malformed);
                Process restarted = probe(h, "recovered");
                try {
                    h.committed(valid);
                    h.committed(malformed);
                    assertThat(Files.exists(temporary.resolve("db-recovered"))).isTrue();
                    assertThat(Files.exists(temporary.resolve("dlt-recovered"))).isTrue();
                } finally {
                    restarted.destroy();
                    if (!restarted.waitFor(10, TimeUnit.SECONDS)) {
                        restarted.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                    }
                }
            } finally {
                if (first.isAlive()) {
                    first.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                }
            }
        }
    }

    private Process probe(Harness harness, String mode) throws Exception {
        var paths = new LinkedHashSet<String>();
        paths.add(Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        paths.add(Path.of(AttachmentConversionKafkaListener.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        var manifests = getClass().getClassLoader().getResources("META-INF/MANIFEST.MF");
        while (manifests.hasMoreElements()) {
            var url = manifests.nextElement();
            if (url.openConnection() instanceof java.net.JarURLConnection jar) {
                paths.add(Path.of(jar.getJarFileURL().toURI()).toString());
            }
        }
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx192m",
                "-cp", String.join(File.pathSeparator, paths), CrashProbe.class.getName(),
                SharedTestKafka.bootstrapServers(), harness.prefix, mode, temporary.toString())
                .redirectErrorStream(true).redirectOutput(temporary.resolve("task-9-probe-" + mode + ".log").toFile()).start();
    }

    public static final class CrashProbe {
        public static void main(String[] args) throws Exception {
            String bootstrap = args[0];
            String prefix = args[1];
            boolean failing = args[2].equals("failing");
            Path directory = Path.of(args[3]);
            var test = new AttachmentConversionKafkaIntegrationTest();
            var producers = new DefaultKafkaProducerFactory<String, String>(Map.of("bootstrap.servers", bootstrap,
                    "key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
                    "value.serializer", "org.apache.kafka.common.serialization.StringSerializer", "acks", "all"));
            test.kafka = new KafkaTemplate<>(producers);
            test.configurer = new ConcurrentKafkaListenerContainerFactoryConfigurer() {
                @Override
                public void configure(ConcurrentKafkaListenerContainerFactory<Object, Object> factory,
                        ConsumerFactory<Object, Object> consumers) {
                    factory.setConsumerFactory(consumers);
                }
            };
            var worker = test.mockWorker();
            var databaseAttempts = new AtomicInteger();
            var dltAttempts = new AtomicInteger();
            when(worker.process(anyString(), any(), any(), anyString(), anyInt())).thenAnswer(call -> {
                Files.writeString(directory.resolve("db-" + args[2]), Integer.toString(databaseAttempts.incrementAndGet()));
                return failing ? delayedFailure() : CompletableFuture.completedFuture(null);
            });
            var dlt = mock(KafkaTemplate.class);
            when(dlt.send(any(ProducerRecord.class))).thenAnswer(call -> {
                Files.writeString(directory.resolve("dlt-" + args[2]), Integer.toString(dltAttempts.incrementAndGet()));
                return failing ? delayedFailure() : test.kafka.send((ProducerRecord<String, String>) call.getArgument(0));
            });
            try (var harness = test.new Harness(worker, dlt, bootstrap, prefix, false)) {
                harness.start();
                new CountDownLatch(1).await();
            }
        }

        private static <T> CompletableFuture<T> delayedFailure() {
            var failed = new CompletableFuture<T>();
            CompletableFuture.delayedExecutor(150, TimeUnit.MILLISECONDS).execute(() ->
                    failed.completeExceptionally(new TransientDataAccessResourceException("delayed technical outage")));
            return failed;
        }
    }

    private KafkaConsumer<String, String> dltConsumer(Harness h) {
        var consumer = new KafkaConsumer<String, String>(Map.of("bootstrap.servers", SharedTestKafka.bootstrapServers(),
                "group.id", UUID.randomUUID().toString(), "auto.offset.reset", "earliest", "enable.auto.commit", false,
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class));
        consumer.assign(List.of(new TopicPartition(h.dltTopic, 0)));
        consumer.seekToBeginning(consumer.assignment());
        return consumer;
    }

    private static void executeViaProducer(KafkaTemplate<String, String> template) {
        var producer = mock(org.apache.kafka.clients.producer.Producer.class);
        when(template.execute(any())).thenAnswer(call -> {
            org.springframework.kafka.core.KafkaOperations.ProducerCallback<String, String, Object> callback = call.getArgument(0);
            return callback.doInKafka(producer);
        });
        when(producer.send(any(ProducerRecord.class), any(org.apache.kafka.clients.producer.Callback.class))).thenAnswer(call -> {
            ProducerRecord<String, String> record = call.getArgument(0);
            org.apache.kafka.clients.producer.Callback callback = call.getArgument(1);
            return template.send(record).whenComplete((result, failure) -> callback.onCompletion(
                    result == null ? null : result.getRecordMetadata(), failure == null ? null : (Exception) failure))
                    .thenApply(result -> result == null ? null : result.getRecordMetadata());
        });
    }

    private AttachmentConversionWorker mockWorker() {
        var worker = mock(AttachmentConversionWorker.class);
        when(worker.process(anyString(), any(), any(), anyString(), anyInt())).thenReturn(CompletableFuture.completedFuture(null));
        when(worker.revoke(anyString(), anySet())).thenReturn(CompletableFuture.completedFuture(null));
        when(worker.drain()).thenReturn(CompletableFuture.completedFuture(null));
        return worker;
    }

    private String accepted(int count) {
        var user = users.save(new User(UUID.randomUUID() + "@kafka.test", "변환 Kafka"));
        stats.save(new UserStats(user));
        var trip = trips.save(new Trip(user.getUserId(), "변환 Kafka", LocalDate.now(), LocalDate.now()));
        var photos = java.util.stream.IntStream.range(0, count).mapToObj(index ->
                new InitialAttachmentUploadUrlRequest.Attachment(index + ".jpg", "image/jpeg", 100L)).toList();
        var prepared = source.prepareBatch(trip.getId(), user.getUserId(),
                new InitialAttachmentUploadUrlRequest(1, count, true, photos));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            batches.findById(prepared.batchId()).orElseThrow().accept(LocalDateTime.now(), AttachmentConversionRoute.KAFKA);
            executions.findById(prepared.executionId()).orElseThrow().closeInput();
            enqueue.enqueue(new AttachmentConversionSeed(Kind.INITIAL, prepared.batchId(), trip.getId(), "kafka-test",
                    prepared.photos().stream().map(photo -> new AttachmentConversionSeed.Photo(
                            photo.itemId(), photo.contentType(), photo.sizeBytes())).toList()));
        });
        return tasks.findByInitialBatchIdAndLane(prepared.batchId(), Lane.LIGHT).orElseThrow().getId();
    }

    private static DerivedPhotoKeys output(DerivedPhotoKeys keys) {
        return new DerivedPhotoKeys(keys.originalKey(), keys.analyzeKey(), keys.previewKey(), keys.displayKey(),
                null, null, null, null, keys.originalSizeBytes(), 50L, 25L, keys.displayKey() == null ? null : 75L);
    }

    private static String message(String id) {
        return "{\"schemaVersion\":1,\"taskId\":\"" + id + "\"}";
    }

    private final class Harness implements AutoCloseable {
        private final String prefix;
        private final String bootstrap;
        private final Topics topics;
        private final String dltTopic;
        private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        private final KafkaListenerEndpointRegistry registry = new KafkaListenerEndpointRegistry();
        private final AtomicBoolean commitFailure = new AtomicBoolean();
        private final AtomicInteger failedCommits = new AtomicInteger();
        private final AtomicInteger consumerThreadViolations = new AtomicInteger();
        private final AtomicBoolean losePartitions = new AtomicBoolean();
        private final AtomicBoolean lostPartitions = new AtomicBoolean();
        private final AtomicBoolean synchronousFailure = new AtomicBoolean();
        private final AtomicInteger synchronousAttempts = new AtomicInteger();
        private final KafkaTemplate<String, String> dlt;
        private AttachmentConversionWorker worker;
        private AttachmentConversionKafkaListener listener;

        private Harness(AttachmentConversionWorker worker, KafkaTemplate<String, String> dlt) throws Exception {
            this(worker, dlt, SharedTestKafka.bootstrapServers(), "conversion-" + UUID.randomUUID(), true);
        }

        private Harness(AttachmentConversionWorker worker, KafkaTemplate<String, String> dlt,
                String bootstrap, String prefix, boolean createTopics) throws Exception {
            this.worker = worker;
            this.dlt = dlt;
            if (mockingDetails(dlt).isMock()) {
                executeViaProducer(dlt);
            }
            this.bootstrap = bootstrap;
            this.prefix = prefix;
            this.topics = new Topics(prefix + "-il", prefix + "-ih", prefix + "-al", prefix + "-ah");
            this.dltTopic = prefix + "-dlt";
            scheduler.setPoolSize(4);
            scheduler.initialize();
            if (createTopics) {
                try (var admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
                    admin.createTopics(List.of(topics.initialLight(), topics.initialHeic(), topics.additionalLight(), topics.additionalHeic(), dltTopic)
                            .stream().map(topic -> new NewTopic(topic, 1, (short) 1)).toList()).all().get(10, TimeUnit.SECONDS);
                }
            }
        }

        private void use(AttachmentConversionWorker worker) throws Exception {
            this.worker = worker;
            start();
        }

        private void start() throws Exception {
            listener = spy(new AttachmentConversionKafkaListener(worker, JsonMapper.builder().build(), dlt,
                    new AttachmentConversionProperties(null, null, null, topics, null), dltTopic, BACKOFF, scheduler, registry));
            doAnswer(call -> {
                if (synchronousFailure.get()) {
                    synchronousAttempts.incrementAndGet();
                    throw new ClassCastException("task9-private?secret=listener");
                }
                return call.callRealMethod();
            }).when(listener).initialLight(any());
            var consumers = new DefaultKafkaConsumerFactory<Object, Object>(Map.of(
                    "bootstrap.servers", bootstrap, "key.deserializer", StringDeserializer.class,
                    "value.deserializer", StringDeserializer.class, "auto.offset.reset", "latest", "enable.auto.commit", true,
                    "session.timeout.ms", 6000, "heartbeat.interval.ms", 1000));
            consumers.addPostProcessor(this::failCommitOrLoseAssignment);
            var configuration = new AttachmentConversionKafkaConfiguration(BACKOFF, 1048576, 524288, 262144);
            var factory = configuration.attachmentConversionKafkaListenerContainerFactory(configurer, consumers, listener, scheduler, registry);
            factory.getContainerProperties().setPollTimeout(100);
            factory.getContainerProperties().setPollTimeoutWhilePaused(Duration.ofMillis(100));
            var methods = List.of("initialLight", "initialHeic", "additionalLight", "additionalHeic");
            var names = List.of(topics.initialLight(), topics.initialHeic(), topics.additionalLight(), topics.additionalHeic());
            var handlers = new DefaultMessageHandlerMethodFactory();
            handlers.afterPropertiesSet();
            for (int index = 0; index < methods.size(); index++) {
                var endpoint = new MethodKafkaListenerEndpoint<Object, Object>();
                endpoint.setId(prefix + "-" + methods.get(index));
                endpoint.setGroupId(prefix + "-" + methods.get(index));
                endpoint.setTopics(names.get(index));
                endpoint.setBean(listener);
                endpoint.setMethod(AttachmentConversionKafkaListener.class.getMethod(methods.get(index), ConsumerRecord.class));
                endpoint.setMessageHandlerMethodFactory(handlers);
                registry.registerListenerContainer(endpoint, factory, false);
            }
            registry.start();
            await().atMost(Duration.ofSeconds(15)).until(() -> registry.getListenerContainers().stream()
                    .allMatch(container -> container.getAssignedPartitions() != null && !container.getAssignedPartitions().isEmpty()));
        }

        private Consumer<Object, Object> failCommitOrLoseAssignment(Consumer<Object, Object> consumer) {
            var owner = new AtomicReference<Thread>();
            var rebalance = new AtomicReference<ConsumerRebalanceListener>();
            return (Consumer<Object, Object>) Proxy.newProxyInstance(Consumer.class.getClassLoader(), new Class<?>[]{Consumer.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("poll")) {
                            owner.compareAndSet(null, Thread.currentThread());
                        }
                        if (Set.of("poll", "pause", "resume", "commitSync", "seek").contains(method.getName())
                                && owner.get() != null && Thread.currentThread() != owner.get()) {
                            consumerThreadViolations.incrementAndGet();
                        }
                        if (method.getName().equals("subscribe") && args.length == 2) {
                            rebalance.set((ConsumerRebalanceListener) args[1]);
                        }
                        if (method.getName().equals("poll") && losePartitions.compareAndSet(true, false)) {
                            rebalance.get().onPartitionsLost(consumer.assignment());
                            lostPartitions.set(true);
                        }
                        if (method.getName().equals("commitSync") && commitFailure.get()
                                && args[0] instanceof Map<?, ?> offsets && offsets.values().stream()
                                .anyMatch(value -> ((OffsetAndMetadata) value).offset() > 0)) {
                            failedCommits.incrementAndGet();
                            throw new CommitFailedException("injected failed commit");
                        }
                        try {
                            return method.invoke(consumer, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }

        private ConsumerRecord<String, String> send(String topic, String value) throws Exception {
            var metadata = kafka.send(new ProducerRecord<>(topic, 0, "7", value)).get(5, TimeUnit.SECONDS).getRecordMetadata();
            return new ConsumerRecord<>(topic, metadata.partition(), metadata.offset(), "7", value);
        }

        private long offset(ConsumerRecord<String, String> record) throws Exception {
            String method = record.topic().equals(topics.initialLight()) ? "initialLight" : record.topic().equals(topics.initialHeic())
                    ? "initialHeic" : record.topic().equals(topics.additionalLight()) ? "additionalLight" : "additionalHeic";
            try (var admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
                var offsets = admin.listConsumerGroupOffsets(prefix + "-" + method).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                var committed = offsets.get(new TopicPartition(record.topic(), record.partition()));
                return committed == null ? -1 : committed.offset();
            }
        }

        private void uncommitted(ConsumerRecord<String, String> record) {
            await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(offset(record)).isLessThanOrEqualTo(record.offset()));
        }

        private void committed(ConsumerRecord<String, String> record) {
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(offset(record)).isGreaterThan(record.offset()));
        }

        private ConcurrentMessageListenerContainer<?, ?> initialLight() {
            return (ConcurrentMessageListenerContainer<?, ?>) registry.getListenerContainer(prefix + "-initialLight");
        }

        @Override
        public void close() throws Exception {
            if (listener != null) {
                listener.close();
            }
            registry.stop();
            registry.destroy();
            worker.drain().get(5, TimeUnit.SECONDS);
            scheduler.shutdown();
        }
    }
}
