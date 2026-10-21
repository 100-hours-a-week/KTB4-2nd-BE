package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.SharedTestKafka;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.service.request.StoryGenerationRequest;
import com.yeodam.yeodambe.trip.entity.RegionOrigin;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yeodam.yeodambe.story.event.StoryCreateResultEvent;
import com.yeodam.yeodambe.story.client.StoryGenerationAiClient;
import com.yeodam.yeodambe.story.repository.StorySelectedPlaceRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.junit.jupiter.api.AfterAll;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest(properties = {
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "app.story.result-listener-enabled=true"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StoryGenerationKafkaResultIntegrationTest {

    private static final String BOOTSTRAP_SERVERS = SharedTestKafka.bootstrapServers();

    private static final String TOPIC = "test-story-results-" + UUID.randomUUID();
    private static final String DLT = TOPIC + "-story-dlt";
    private static final String GROUP = "test-story-consumer-" + UUID.randomUUID();
    @Autowired private StoryGenerationAcceptanceService acceptance;
    @Autowired private StoryGenerationCancelService cancellation;
    @Autowired private StoryGenerationTimeoutService timeout;
    @Autowired private StoryGenerationStatusSyncService statusSync;
    @Autowired private StoryGenerationJobRepository jobs;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripDetailPlaceRepository folders;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private StoredFileRepository files;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private KafkaTemplate<String, String> kafka;
    @Autowired private ObjectMapper mapper;
    @MockitoSpyBean private StorySelectedPlaceRepository selectedPlaces;
    @MockitoBean private StoryGenerationAiClient ai;

    @DynamicPropertySource
    static void configureKafka(DynamicPropertyRegistry registry) throws Exception {
        SharedTestKafka.registerBootstrapServers(registry);
        try (var admin = admin()) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1), new NewTopic(DLT, 1, (short) 1)))
                    .all().get(15, TimeUnit.SECONDS);
        }
        registry.add("app.kafka.topics.story-create-result", () -> TOPIC);
        registry.add("app.story.result-consumer-group", () -> GROUP);
        registry.add("app.kafka.dead-letter-topics.story-create-result", () -> DLT);
    }

    @AfterAll
    static void removeTestTopic(@Autowired KafkaListenerEndpointRegistry registry) throws Exception {
        registry.stop();
        try (var admin = admin()) {
            admin.deleteTopics(List.of(TOPIC, DLT)).all().get(15, TimeUnit.SECONDS);
        }
    }

    private static AdminClient admin() {
        return AdminClient.create(Map.of("bootstrap.servers", BOOTSTRAP_SERVERS));
    }

    private final ch.qos.logback.classic.Logger workflowLogger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(com.yeodam.yeodambe.common.logging.WorkflowLog.class);
    private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> workflowEvents =
            new ch.qos.logback.core.read.ListAppender<>();

    private User owner;
    private Trip trip;
    private TripDetailPlace folder;
    private StoredFile file;
    private TripAttachment photo;
    private StoryGenerationJob job;

    @BeforeEach
    void prepare() {
        workflowEvents.list = new java.util.concurrent.CopyOnWriteArrayList<>();
        workflowEvents.start();
        workflowLogger.addAppender(workflowEvents);
        owner = users.save(new User(UUID.randomUUID() + "@yeodam.test", "스토리회원"));
        trip = trips.save(new Trip(owner.getUserId(), "여행",
                LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 12)));
        jdbc.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", trip.getId());
        var takenAt = LocalDateTime.of(2026, 10, 11, 10, 0);
        folder = folders.save(TripDetailPlace.fromAnalysis(trip.getId(), 1, "장소",
                BigDecimal.ZERO, BigDecimal.ZERO, takenAt, takenAt, null));
        file = files.save(StoredFile.uploaded(owner.getUserId(), "photo.jpg",
                "original/" + UUID.randomUUID(), "image/jpeg"));
        var attachment = TripAttachment.initial(trip.getId(), file.getId(), "analysis/photo.jpg", "preview/photo.jpg");
        attachment.classify(folder.getId(), RegionOrigin.EXIF, takenAt, BigDecimal.ZERO, BigDecimal.ZERO, 80);
        photo = attachments.saveAndFlush(attachment);
        job = acceptance.accept(owner.getUserId(), trip.getId(),
                new StoryGenerationRequest(Story.Mood.EMOTIONAL, List.of(folder.getId())), "completion-test");
    }

    @AfterEach
    void cleanUp() {
        workflowLogger.detachAppender(workflowEvents);
        if (trip == null) {
            return;
        }
        jdbc.update("UPDATE trips SET current_story_id = NULL WHERE trip_id = ?", trip.getId());
        jdbc.update("DELETE FROM story_generation_outbox WHERE generation_id IN "
                + "(SELECT generation_id FROM story_generation_jobs WHERE trip_id = ?)", trip.getId());
        jdbc.update("DELETE FROM story_generation_places WHERE generation_id IN "
                + "(SELECT generation_id FROM story_generation_jobs WHERE trip_id = ?)", trip.getId());
        jdbc.update("DELETE FROM story_generation_jobs WHERE trip_id = ?", trip.getId());
        jdbc.update("DELETE FROM story_blocks WHERE story_id IN (SELECT story_id FROM stories WHERE trip_id = ?)", trip.getId());
        jdbc.update("DELETE FROM story_selected_places WHERE story_id IN (SELECT story_id FROM stories WHERE trip_id = ?)", trip.getId());
        jdbc.update("DELETE FROM stories WHERE trip_id = ?", trip.getId());
        jdbc.update("DELETE FROM trip_attachments WHERE trip_id = ?", trip.getId());
        jdbc.update("DELETE FROM trip_detail_places WHERE trip_id = ?", trip.getId());
        jdbc.update("DELETE FROM trips WHERE trip_id = ?", trip.getId());
        if (file != null) {
            files.deleteById(file.getId());
        }
        users.deleteById(owner.getUserId());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void cancellationPersistsAndLateKafkaResultsCannotCreateStory(boolean taskMissing) throws Exception {
        if (taskMissing) {
            org.mockito.Mockito.when(ai.cancel(trip.getId())).thenThrow(
                    new com.yeodam.yeodambe.story.exception.StoryGenerationAiRequestException(
                            com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage.AI_CANCEL_REQUEST_FAILED,
                            404, "TASK_NOT_FOUND"));
        } else {
            org.mockito.Mockito.when(ai.cancel(trip.getId())).thenReturn(new StoryGenerationAiResponse(
                    trip.getId(), job.getExecutionId(), StoryGenerationAiResponse.Status.CANCELED,
                    null, null, null, null));
        }

        cancellation.cancel(owner.getUserId(), job.getId());

        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(StoryGenerationJob.Status.CANCELED);
        assertThat(jobs.existsByTripIdAndStatusIn(trip.getId(), List.of(
                StoryGenerationJob.Status.QUEUED, StoryGenerationJob.Status.PROCESSING))).isFalse();
        long canceled = send(new StoryCreateResultEvent("story_result", trip.getId(),
                job.getExecutionId(), StoryGenerationAiResponse.Status.CANCELED, null, null));
        awaitCommitted(canceled);
        long completed = send(completionEvent(job.getExecutionId()));
        awaitCommitted(completed);

        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(StoryGenerationJob.Status.CANCELED);
        assertThat(count("stories")).isZero();
        var next = acceptance.accept(owner.getUserId(), trip.getId(),
                new StoryGenerationRequest(Story.Mood.EMOTIONAL, List.of(folder.getId())), "regeneration-test");
        assertThat(next.getId()).isNotEqualTo(job.getId());
        assertThat(next.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
    }

    @Test
    void kafkaCompletionCommitsResultAndDuplicateDoesNotCreateAnotherStory() throws Exception {
        long first = send(completionEvent(job.getExecutionId()));
        awaitCommitted(first);
        assertCompletedOnce();
        Long storyId = jobs.findById(job.getId()).orElseThrow().getStoryId();

        long repeated = send(completionEvent(job.getExecutionId()));
        awaitCommitted(repeated);
        assertCompletedOnce();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStoryId()).isEqualTo(storyId);
        assertThat(terminalSuccessLogs()).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = StoryGenerationAiResponse.Status.class, names = {"FAILED", "CANCELED"})
    void kafkaTerminalResultUpdatesJobWithoutSavingStory(StoryGenerationAiResponse.Status status) throws Exception {
        var error = status == StoryGenerationAiResponse.Status.FAILED
                ? new StoryGenerationAiResponse.Failure("WORKER_DEAD", "worker unavailable", null) : null;
        long offset = send(new StoryCreateResultEvent("story_result", trip.getId(),
                job.getExecutionId(), status, null, error));
        awaitCommitted(offset);

        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus().name()).isEqualTo(status.name());
        assertThat(count("stories")).isZero();
        assertThat(jobs.findById(job.getId()).orElseThrow().getErrorCode())
                .isEqualTo(status == StoryGenerationAiResponse.Status.FAILED ? "WORKER_DEAD" : null);
    }

    @Test
    void unrelatedAndUnknownExecutionResultsDoNotPreventFollowingValidResult() throws Exception {
        kafka.send(TOPIC, trip.getId().toString(), "{\"type\":\"process_result\"}")
                .get(10, TimeUnit.SECONDS);
        long unknown = send(completionEvent("unknown-" + UUID.randomUUID()));
        awaitCommitted(unknown);
        assertNoResultSaved();

        long valid = send(completionEvent(job.getExecutionId()));
        awaitCommitted(valid);
        assertCompletedOnce();
    }

    @Test
    void httpCompletedStatusWaitsForKafkaResultBeforeFinishingJob() throws Exception {
        when(ai.getStatus(trip.getId())).thenReturn(new StoryGenerationAiResponse(
                trip.getId(), job.getExecutionId(), StoryGenerationAiResponse.Status.COMPLETED,
                null, null, null, null));
        statusSync.synchronize(job.getId());
        assertNoResultSaved();

        long offset = send(completionEvent(job.getExecutionId()));
        awaitCommitted(offset);
        assertCompletedOnce();
    }

    @Test
    void failedSaveRollsBackThenKafkaRetriesSameRecordAndCommitsOnce() throws Exception {
        var originalAnswer = mockingDetails(selectedPlaces).getMockCreationSettings().getDefaultAnswer();
        var attempts = new AtomicInteger();
        var retryEntered = new CountDownLatch(1);
        var allowRetry = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                throw new DataIntegrityViolationException("injected temporary storage failure");
            }
            retryEntered.countDown();
            if (!allowRetry.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Retry was not released");
            }
            return originalAnswer.answer(invocation);
        }).when(selectedPlaces).saveAll(any());

        long offset = send(completionEvent(job.getExecutionId()));
        try {
            assertThat(retryEntered.await(15, TimeUnit.SECONDS)).isTrue();
            assertNoResultSaved();
            assertThat(terminalSuccessLogs()).isEmpty();
            try (var client = admin()) {
                var committed = client.listConsumerGroupOffsets(GROUP)
                        .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)
                        .get(new TopicPartition(TOPIC, 0));
                if (committed != null) {
                    assertThat(committed.offset()).isLessThanOrEqualTo(offset);
                }
            }
        } finally {
            allowRetry.countDown();
        }
        awaitCommitted(offset);

        assertThat(attempts.get()).isEqualTo(2);
        assertCompletedOnce();
        assertThat(terminalSuccessLogs()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_selected_places WHERE trip_place_id = ?",
                Integer.class, folder.getId())).isEqualTo(1);
    }

    @Test
    void malformedJsonIsPreservedAndFollowingValidResultCompletes() throws Exception {
        String malformed = "{invalid-json";
        var offset = kafka.send(TOPIC, trip.getId().toString(), malformed)
                .get(10, TimeUnit.SECONDS).getRecordMetadata().offset();
        awaitCommitted(offset);
        assertDeadLetterContains(malformed);
        assertNoResultSaved();

        awaitCommitted(send(completionEvent(job.getExecutionId())));
        assertCompletedOnce();
    }

    @Test
    void invalidPhotoResultFailsIdentifiedJobAndDoesNotBlockFollowingRecord() throws Exception {
        var response = completed(job.getExecutionId(), photo.getId() + 100000);
        var invalid = new StoryCreateResultEvent("story_result", trip.getId(), job.getExecutionId(),
                StoryGenerationAiResponse.Status.COMPLETED, response.result(), null);
        String json = mapper.writeValueAsString(invalid);
        awaitCommitted(send(invalid));
        assertDeadLetterContains(json);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(StoryGenerationJob.Status.FAILED);
        assertThat(count("stories")).isZero();
        awaitCommitted(send(completionEvent("unknown-" + UUID.randomUUID())));
    }

    @Test
    void persistentStorageFailureRetriesThreeTimesThenPreservesResult() throws Exception {
        var attempts = new AtomicInteger();
        doAnswer(invocation -> {
            attempts.incrementAndGet();
            throw new DataIntegrityViolationException("injected persistent storage failure");
        }).when(selectedPlaces).saveAll(any());
        var event = completionEvent(job.getExecutionId());
        awaitCommitted(send(event));
        assertDeadLetterContains(mapper.writeValueAsString(event));
        assertThat(attempts.get()).isEqualTo(4);
        assertThat(count("stories")).isZero();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(StoryGenerationJob.Status.FAILED);
        awaitCommitted(send(completionEvent("unknown-" + UUID.randomUUID())));
    }

    private void assertDeadLetterContains(String expected) throws Exception {
        var properties = Map.<String, Object>of(
                "bootstrap.servers", BOOTSTRAP_SERVERS,
                "group.id", "dlt-check-" + UUID.randomUUID(),
                "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(properties)) {
            consumer.assign(List.of(new TopicPartition(DLT, 0)));
            consumer.seekToBeginning(List.of(new TopicPartition(DLT, 0)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                for (var record : consumer.poll(java.time.Duration.ofMillis(200))) {
                    if (expected.equals(record.value())) {
                        assertThat(record.headers().lastHeader("kafka_dlt-original-topic")).isNotNull();
                        return;
                    }
                }
            }
            throw new AssertionError("Original failed message was not preserved in DLT");
        }
    }

    @Test
    void kafkaLateCompletionForTimedOutJobIsConsumedWithoutSavingStory() throws Exception {
        jdbc.update("""
                UPDATE story_generation_outbox
                SET published_at = CURRENT_TIMESTAMP(6) - INTERVAL 2 HOUR
                WHERE generation_id = ?
                """, job.getId());
        timeout.expire(job.getId(), LocalDateTime.now());

        awaitCommitted(send(completionEvent(job.getExecutionId())));

        assertThat(jobs.findById(job.getId()).orElseThrow().getErrorCode()).isEqualTo("STORY_GENERATION_TIMEOUT");
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(StoryGenerationJob.Status.FAILED);
        assertThat(count("stories")).isZero();
        assertThat(trips.findById(trip.getId()).orElseThrow().getCurrentStoryId()).isNull();
    }

    private StoryCreateResultEvent completionEvent(String executionId) {
        var response = completed(executionId, photo.getId());
        return new StoryCreateResultEvent("story_result", trip.getId(), executionId,
                StoryGenerationAiResponse.Status.COMPLETED, response.result(), null);
    }

    private long send(StoryCreateResultEvent event) throws Exception {
        return kafka.send(TOPIC, trip.getId().toString(), mapper.writeValueAsString(event))
                .get(10, TimeUnit.SECONDS).getRecordMetadata().offset();
    }

    private void awaitCommitted(long recordOffset) throws Exception {
        try (var client = admin()) {
            var partition = new TopicPartition(TOPIC, 0);
            await(() -> {
                try {
                    var committed = client.listConsumerGroupOffsets(GROUP)
                            .partitionsToOffsetAndMetadata().get(2, TimeUnit.SECONDS).get(partition);
                    return committed != null && committed.offset() > recordOffset;
                } catch (Exception failure) {
                    throw new AssertionError("Cannot inspect consumer offset", failure);
                }
            });
        }
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Kafka processing timed out");
            }
            Thread.sleep(100);
        }
    }

    private List<ch.qos.logback.classic.spi.ILoggingEvent> terminalSuccessLogs() {
        return workflowEvents.list.stream()
                .filter(event -> job.getExecutionId().equals(event.getMDCPropertyMap().get("job_id")))
                .filter(event -> event.getKeyValuePairs().stream()
                        .anyMatch(pair -> "event".equals(pair.key) && "workflow_finished".equals(pair.value)))
                .filter(event -> event.getKeyValuePairs().stream()
                        .anyMatch(pair -> "result".equals(pair.key) && "success".equals(pair.value)))
                .toList();
    }

    private void assertCompletedOnce() {
        assertThat(count("stories")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_blocks WHERE story_id IN "
                + "(SELECT story_id FROM stories WHERE trip_id = ?)", Integer.class, trip.getId())).isEqualTo(1);
        var saved = jobs.findById(job.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(StoryGenerationJob.Status.COMPLETED);
        assertThat(saved.getProgress()).isEqualTo(100);
        assertThat(saved.getStoryId()).isNotNull();
        assertThat(trips.findById(trip.getId()).orElseThrow().getCurrentStoryId()).isEqualTo(saved.getStoryId());
    }

    private void assertNoResultSaved() {
        assertThat(count("stories")).isZero();
        var saved = jobs.findById(job.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
        assertThat(saved.getStoryId()).isNull();
        assertThat(trips.findById(trip.getId()).orElseThrow().getCurrentStoryId()).isNull();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE trip_id = ?", Integer.class, trip.getId());
    }

    private StoryGenerationAiResponse completed(String executionId, Long photoId) {
        return new StoryGenerationAiResponse(trip.getId(), executionId, StoryGenerationAiResponse.Status.COMPLETED,
                new StoryGenerationAiResponse.Progress(4, 4), null,
                new StoryGenerationAiResponse.Result("여행 이야기", List.of(
                        new StoryGenerationAiResponse.Block(1, folder.getId(), photoId, "첫날", "사진 설명", "산책했다."))),
                null);
    }
}
