package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;
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
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.yeodam.yeodambe.common.websocket.service.WebSocketEventPublisher;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEvent;
import com.yeodam.yeodambe.story.service.response.StoryGenerationStatusNotification;
import static org.mockito.Mockito.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "app.story.mongo-native-generation-enabled=false")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StoryGenerationCompletionServiceTest {
    @Autowired private StoryGenerationCompletionService service;
    @Autowired private StoryGenerationPreparationService preparation;
    @MockitoSpyBean private StoryGenerationFinalizationService finalization;
    @MockitoSpyBean private StoryContentStorageService contentStorage;
    @Autowired private StoryContentRepository contents;
    @Autowired private StoryGenerationAcceptanceService acceptance;
    @Autowired private StoryGenerationTerminalService terminal;
    @Autowired private StoryGenerationResultRecoveryService recovery;
    @Autowired private StoryGenerationDiscardService discard;
    @Autowired private StoryGenerationPublicationStateService publicationState;
    @Autowired private StoryGenerationTimeoutService timeout;
    @Autowired private StoryGenerationProgressService progress;
    @Autowired private StoryGenerationStatusService status;
    @Autowired private StoryGenerationJobRepository jobs;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripDetailPlaceRepository folders;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private StoredFileRepository files;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private WebSocketEventPublisher publisher;

    private User owner;
    private Trip trip;
    private TripDetailPlace folder;
    private StoredFile file;
    private StoredFile extraFile;
    private TripAttachment photo;
    private StoryGenerationJob job;

    @BeforeEach
    void prepare() {
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
                new StoryGenerationRequest(Story.Mood.PLAIN, List.of(folder.getId())), "completion-test");
        clearInvocations(publisher);
    }

    @AfterEach
    void cleanUp() {
        if (trip == null) {
            return;
        }
        jdbc.update("UPDATE trips SET current_story_id = NULL WHERE trip_id = ?", trip.getId());
        contents.deleteAllById(jdbc.queryForList(
                "SELECT story_id FROM stories WHERE trip_id = ?", Long.class, trip.getId()));
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
        if (extraFile != null) {
            files.deleteById(extraFile.getId());
        }
        users.deleteById(owner.getUserId());
    }

    @Test
    void 실행_ID로_저장된_생성_ID를_찾고_알_수_없는_실행은_빈_값을_반환한다() {
        var found = jobs.findByExecutionId(job.getExecutionId()).orElseThrow();

        assertThat(found.getId()).isEqualTo(job.getId());
        assertThat(found.getTripId()).isEqualTo(trip.getId());
        assertThat(jobs.findByExecutionId("unknown-" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void 스토리_완료를_롤백하면_발행하거나_시퀀스를_증가시키지_않는다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));
            verifyNoInteractions(publisher);
            transaction.setRollbackOnly();
        });
        verifyNoInteractions(publisher);
        assertNoResultSaved();
        assertThat(jdbc.queryForObject("SELECT change_sequence FROM trips WHERE trip_id = ?", Long.class, trip.getId())).isZero();
    }

    @Test
    void 스토리와_블록과_선택_정보와_여행_연결과_작업_완료를_함께_커밋한다() {
        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));
        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));
        var captor = ArgumentCaptor.forClass(WebSocketEvent.class);
        verify(publisher).publish(captor.capture());
        verifyNoMoreInteractions(publisher);
        var data = new tools.jackson.databind.ObjectMapper().valueToTree(captor.getValue().payload()).path("data");
        assertThat(data.path("eventType").asString()).isEqualTo("STORY_GENERATION_COMPLETED");
        assertThat(data.path("statusVersion").asLong()).isEqualTo(2);
        assertThat(data.path("listItem").path("sequence").asLong()).isEqualTo(1);
        assertThat(data.path("listItem").path("mood").asString()).isEqualTo("PLAIN");
        assertThat(data.path("listItem").path("storySummary").asString()).isEqualTo("여행 이야기");

        assertCompletedOnce();
        Long storyId = jobs.findById(job.getId()).orElseThrow().getStoryId();
        assertThat(jdbc.queryForObject("SELECT processing_status FROM stories WHERE story_id = ?",
                String.class, storyId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT mood FROM stories WHERE story_id = ?", String.class, storyId))
                .isEqualTo("PLAIN");
        assertThat(jdbc.queryForObject("SELECT trip_attachment_id FROM story_blocks WHERE story_id = ?",
                Long.class, storyId)).isEqualTo(photo.getId());
        assertThat(jdbc.queryForObject("SELECT trip_place_id FROM story_selected_places WHERE story_id = ?",
                Long.class, storyId)).isEqualTo(folder.getId());
        assertThat(jdbc.queryForObject("SELECT source_trip_place_id FROM story_blocks WHERE story_id = ?",
                Long.class, storyId)).isEqualTo(folder.getId());
        assertThat(jdbc.queryForObject("SELECT source_date FROM story_blocks WHERE story_id = ?",
                java.time.LocalDate.class, storyId)).isEqualTo(folder.getStartedAt().toLocalDate());
    }

    @Test
    void 완료를_반복해도_스토리나_블록을_추가_저장하지_않는다() {
        var response = completed(job.getExecutionId(), photo.getId());
        service.complete(job.getId(), response);
        Long firstStoryId = jobs.findById(job.getId()).orElseThrow().getStoryId();

        service.complete(job.getId(), response);

        assertCompletedOnce();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStoryId()).isEqualTo(firstStoryId);
    }

    @Test
    void 오래된_실행_ID는_스토리_저장_전에_거부한다() {
        assertThatThrownBy(() -> service.complete(job.getId(), completed("old-execution", photo.getId())))
                .isInstanceOf(IllegalArgumentException.class);

        assertNoResultSaved();
    }

    @Test
    void 발행한_요청에_없는_사진을_거부한다() {
        assertThatThrownBy(() -> service.complete(job.getId(), completed(job.getExecutionId(), Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class);

        assertNoResultSaved();
    }

    @ParameterizedTest
    @EnumSource(value = StoryGenerationJob.Status.class, names = {"FAILED", "CANCELED"})
    void 종료된_작업에_늦게_도착한_완료_결과를_저장하지_않는다(StoryGenerationJob.Status status) {
        var error = status == StoryGenerationJob.Status.FAILED
                ? new StoryGenerationAiResponse.Failure("WORKER_DEAD", "AI failure", null) : null;
        terminal.apply(job.getId(), new StoryGenerationAiResponse(trip.getId(), job.getExecutionId(),
                StoryGenerationAiResponse.Status.valueOf(status.name()), null, null, null, error));

        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));

        assertThat(count("stories")).isZero();
        assertThat(trips.findById(trip.getId()).orElseThrow().getCurrentStoryId()).isNull();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(status);
        assertThat(jobs.findById(job.getId()).orElseThrow().getErrorCode())
                .isEqualTo(status == StoryGenerationJob.Status.FAILED ? "WORKER_DEAD" : null);
    }

    @Test
    void 늦은_발행_수신_확인은_완료된_작업을_덮어쓰지_않는다() {
        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));
        Long outboxId = jdbc.queryForObject("SELECT outbox_id FROM story_generation_outbox WHERE generation_id = ?",
                Long.class, job.getId());

        var outbox = jdbc.queryForMap("SELECT topic, trip_id, payload FROM story_generation_outbox WHERE outbox_id = ?", outboxId);
        publicationState.recordPublished(outboxId, new OutboxReceipt(
                outbox.get("topic").toString(), outbox.get("trip_id").toString(),
                outbox.get("payload").toString(), Instant.now()));

        assertCompletedOnce();
        assertThat(jobs.findById(job.getId()).orElseThrow().getErrorCode()).isNull();
    }

    @Test
    void 블록_외래키_실패_시_이미_삽입한_스토리와_선택_정보를_롤백한다() {
        attachments.deleteById(photo.getId());

        assertThatThrownBy(() -> service.complete(job.getId(), completed(job.getExecutionId(), photo.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertNoResultSaved();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_selected_places WHERE trip_place_id = ?",
                Integer.class, folder.getId())).isZero();
    }

    @Test
    void 동시_완료_호출은_결과를_한_번만_커밋한다() throws Exception {
        var response = completed(job.getExecutionId(), photo.getId());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> completeAfterStart(ready, start, response));
            var second = executor.submit(() -> completeAfterStart(ready, start, response));
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }

        assertCompletedOnce();
    }

    @Test
    void 만료된_작업은_실패로_저장하고_늦은_결과와_진행률을_무시한다() {
        jdbc.update("""
                UPDATE story_generation_outbox
                SET published_at = CURRENT_TIMESTAMP(6) - INTERVAL 2 HOUR
                WHERE generation_id = ?
                """, job.getId());
        assertThat(timeout.expire(job.getId(), LocalDateTime.now())).isTrue();

        progress.updateProcessing(job.getId(), new StoryGenerationAiResponse(trip.getId(), job.getExecutionId(),
                StoryGenerationAiResponse.Status.PROCESSING, new StoryGenerationAiResponse.Progress(4, 4),
                null, null, null));
        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));

        var saved = jobs.findById(job.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(StoryGenerationJob.Status.FAILED);
        assertThat(saved.getErrorCode()).isEqualTo("STORY_GENERATION_TIMEOUT");
        assertThat(count("stories")).isZero();
        assertThat(trips.findById(trip.getId()).orElseThrow().getCurrentStoryId()).isNull();
        var response = status.getStatus(owner.getUserId(), job.getId());
        assertThat(response.status()).isEqualTo(StoryGenerationJob.Status.FAILED);
        assertThat(response.error().code()).isEqualTo("STORY_GENERATION_FAILED");
    }

    @Test
    void 다음_스케줄러_실행_전이라도_늦은_완료_응답은_작업을_만료시킨다() {
        jdbc.update("""
                UPDATE story_generation_outbox
                SET published_at = CURRENT_TIMESTAMP(6) - INTERVAL 2 HOUR
                WHERE generation_id = ?
                """, job.getId());
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));
            verifyNoInteractions(publisher);
        });
        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));
        var captor = ArgumentCaptor.forClass(WebSocketEvent.class);
        verify(publisher).publish(captor.capture());
        verifyNoMoreInteractions(publisher);
        var notification = (StoryGenerationStatusNotification) captor.getValue().payload().data();
        assertThat(notification.generationId()).isEqualTo(job.getId());
        assertThat(notification.status()).isEqualTo(StoryGenerationJob.Status.FAILED);
        assertThat(notification.statusVersion()).isEqualTo(2);
        assertThat(notification.error().code()).isEqualTo("STORY_GENERATION_FAILED");
        assertThat(jobs.findById(job.getId()).orElseThrow().getErrorCode()).isEqualTo("STORY_GENERATION_TIMEOUT");
        assertThat(count("stories")).isZero();
    }

    @Test
    void 늦은_완료를_롤백하면_시간_초과를_발행하거나_저장하지_않는다() {
        jdbc.update("""
                UPDATE story_generation_outbox
                SET published_at = CURRENT_TIMESTAMP(6) - INTERVAL 2 HOUR
                WHERE generation_id = ?
                """, job.getId());
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));
            transaction.setRollbackOnly();
        });
        verifyNoInteractions(publisher);
        var saved = jobs.findById(job.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
        assertThat(saved.getStatusVersion()).isEqualTo(1);
        assertThat(saved.getErrorCode()).isNull();
        assertThat(count("stories")).isZero();
    }

    @Test
    void 발행_확인_전_완료_응답은_기존_생성_기한을_무시한다() {
        jdbc.update("""
                UPDATE story_generation_jobs
                SET created_at = CURRENT_TIMESTAMP(6) - INTERVAL 2 HOUR
                WHERE generation_id = ?
                """, job.getId());
        assertThat(jdbc.queryForObject(
                "SELECT published_at FROM story_generation_outbox WHERE generation_id = ?",
                LocalDateTime.class, job.getId())).isNull();

        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));

        assertCompletedOnce();
    }

    @Test
    void 완료_후_시간_초과를_확인해도_저장된_스토리를_유지한다() {
        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));
        assertThat(timeout.expire(job.getId(), LocalDateTime.now().plusHours(2))).isTrue();
        assertCompletedOnce();
    }

    @Test
    void 완료나_발행_없이_내용을_한_번만_준비한다() {
        var response = completed(job.getExecutionId(), photo.getId());

        Long storyId = preparation.prepare(job.getId(), response).orElseThrow();
        Long retriedStoryId = preparation.prepare(job.getId(), response).orElseThrow();

        assertThat(retriedStoryId).isEqualTo(storyId);
        assertThat(count("stories")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT processing_status FROM stories WHERE story_id = ?", String.class, storyId))
                .isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM story_blocks WHERE story_id = ?", Integer.class, storyId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM story_selected_places WHERE story_id = ?", Integer.class, storyId))
                .isEqualTo(1);
        var saved = jobs.findById(job.getId()).orElseThrow();
        assertThat(saved.getStoryId()).isEqualTo(storyId);
        assertThat(saved.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
        assertThat(saved.getStatusVersion()).isEqualTo(1);
        assertThat(trips.findById(trip.getId()).orElseThrow().getCurrentStoryId()).isNull();
        assertThat(status.getStatus(owner.getUserId(), job.getId()).storyId()).isNull();
        verifyNoInteractions(publisher);
    }

    @Test
    void 준비한_스토리와_하위_데이터를_함께_롤백한다() {
        var response = completed(job.getExecutionId(), photo.getId());
        Long storyId = new TransactionTemplate(transactionManager).execute(transaction -> {
            Long preparedId = preparation.prepare(job.getId(), response).orElseThrow();
            transaction.setRollbackOnly();
            return preparedId;
        });

        assertNoResultSaved();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM story_blocks WHERE story_id = ?", Integer.class, storyId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM story_selected_places WHERE story_id = ?", Integer.class, storyId))
                .isZero();
        assertThat(preparation.prepare(job.getId(), response)).isPresent();
        assertThat(count("stories")).isEqualTo(1);
    }

    @Test
    void 취소된_작업의_스토리를_준비하지_않는다() {
        terminal.confirmCancellation(owner.getUserId(), job.getId());
        clearInvocations(publisher);

        assertThat(preparation.prepare(job.getId(), completed(job.getExecutionId(), photo.getId())))
                .isEmpty();

        assertThat(count("stories")).isZero();
        verifyNoInteractions(publisher);
    }

    @Test
    void 늦은_결과는_스토리_준비_없이_작업을_만료시킨다() {
        jdbc.update("""
                UPDATE story_generation_outbox
                SET published_at = CURRENT_TIMESTAMP(6) - INTERVAL 2 HOUR
                WHERE generation_id = ?
                """, job.getId());

        assertThat(preparation.prepare(job.getId(), completed(job.getExecutionId(), photo.getId())))
                .isEmpty();

        var saved = jobs.findById(job.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(StoryGenerationJob.Status.FAILED);
        assertThat(saved.getErrorCode()).isEqualTo("STORY_GENERATION_TIMEOUT");
        assertThat(saved.getStoryId()).isNull();
        assertThat(count("stories")).isZero();
    }

    @Test
    void 발행_없이_준비된_문서를_UTC_타임스탬프로_읽는다() {
        Long storyId = preparation.prepare(job.getId(),
                completed(job.getExecutionId(), photo.getId())).orElseThrow();
        jdbc.update("""
                UPDATE story_blocks SET created_at = ?, updated_at = ? WHERE story_id = ?
                """, LocalDateTime.of(2026, 11, 4, 10, 0),
                LocalDateTime.of(2026, 11, 4, 11, 0), storyId);

        var document = preparation.readPreparedContent(job.getId(), storyId, ZoneOffset.UTC)
                .orElseThrow();

        assertThat(document.getId()).isEqualTo(storyId);
        assertThat(document.getSelectedPlaceIds()).containsExactly(folder.getId());
        assertThat(document.getBlocks()).hasSize(1);
        var block = document.getBlocks().getFirst();
        assertThat(block.tripAttachmentId()).isEqualTo(photo.getId());
        assertThat(block.sourceTripPlaceId()).isEqualTo(folder.getId());
        assertThat(block.sourceDate()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(block.createdAt()).isEqualTo(Instant.parse("2026-11-04T10:00:00Z"));
        assertThat(block.updatedAt()).isEqualTo(Instant.parse("2026-11-04T11:00:00Z"));
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(StoryGenerationJob.Status.QUEUED);
        verifyNoInteractions(publisher);
    }

    @Test
    void 삭제된_ID를_포함해_표시_순서대로_블록을_읽는다() {
        Long storyId = preparation.prepare(job.getId(),
                completed(job.getExecutionId(), photo.getId())).orElseThrow();
        Long originalBlockId = jdbc.queryForObject(
                "SELECT story_block_id FROM story_blocks WHERE story_id = ?", Long.class, storyId);
        extraFile = files.save(StoredFile.uploaded(owner.getUserId(), "extra.jpg",
                "original/" + UUID.randomUUID(), "image/jpeg"));
        var extraPhoto = attachments.saveAndFlush(TripAttachment.initial(
                trip.getId(), extraFile.getId(), "analysis/extra.jpg", "preview/extra.jpg"));
        jdbc.update("""
                INSERT INTO story_blocks
                    (story_id, trip_attachment_id, source_trip_place_id, source_date,
                     order_number, day_label, detail_summary, memo)
                VALUES (?, ?, ?, ?, 1, 'first', 'detail', 'memo')
                """, storyId, extraPhoto.getId(), folder.getId(), LocalDate.of(2026, 10, 11));
        Long extraBlockId = jdbc.queryForObject(
                "SELECT story_block_id FROM story_blocks WHERE story_id = ? AND trip_attachment_id = ?",
                Long.class, storyId, extraPhoto.getId());

        var tied = preparation.readPreparedContent(job.getId(), storyId, ZoneOffset.UTC).orElseThrow();
        assertThat(tied.getBlocks()).extracting(com.yeodam.yeodambe.story.document.StoryContentBlock::id)
                .containsExactly(originalBlockId, extraBlockId);

        jdbc.update("UPDATE story_blocks SET order_number = 2 WHERE story_block_id = ?", originalBlockId);
        jdbc.update("UPDATE story_blocks SET deleted_at = ? WHERE story_block_id = ?",
                LocalDateTime.of(2026, 11, 4, 12, 0), extraBlockId);
        var reordered = preparation.readPreparedContent(job.getId(), storyId, ZoneOffset.UTC).orElseThrow();
        assertThat(reordered.getBlocks()).extracting(com.yeodam.yeodambe.story.document.StoryContentBlock::id)
                .containsExactly(extraBlockId, originalBlockId);
        assertThat(reordered.getBlocks().getFirst().deletedAt())
                .isEqualTo(Instant.parse("2026-11-04T12:00:00Z"));
        assertThat(reordered.getNextBlockId()).isEqualTo(extraBlockId + 1);
    }

    @Test
    void 다른_스토리_ID의_준비된_문서는_거부한다() {
        Long storyId = preparation.prepare(job.getId(),
                completed(job.getExecutionId(), photo.getId())).orElseThrow();

        assertThatThrownBy(() -> preparation.readPreparedContent(job.getId(), storyId + 1, ZoneOffset.UTC))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 취소_후에는_준비된_문서를_건너뛴다() {
        Long storyId = preparation.prepare(job.getId(),
                completed(job.getExecutionId(), photo.getId())).orElseThrow();
        terminal.confirmCancellation(owner.getUserId(), job.getId());

        assertThat(preparation.readPreparedContent(job.getId(), storyId, ZoneOffset.UTC)).isEmpty();
    }

    @Test
    void 삭제된_준비_스토리를_거부한다() {
        Long storyId = preparation.prepare(job.getId(),
                completed(job.getExecutionId(), photo.getId())).orElseThrow();
        jdbc.update("UPDATE stories SET deleted_at = CURRENT_TIMESTAMP(6) WHERE story_id = ?", storyId);

        assertThatThrownBy(() -> preparation.readPreparedContent(job.getId(), storyId, ZoneOffset.UTC))
                .isInstanceOf(IllegalStateException.class);
    }

    private void completeAfterStart(CountDownLatch ready, CountDownLatch start,
                                    StoryGenerationAiResponse response) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Test start timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        service.complete(job.getId(), response);
    }

    @Test
    void 준비된_스토리를_한_번만_완료하고_커밋된_결과를_발행한다() {
        Long storyId = preparation.prepare(job.getId(),
                completed(job.getExecutionId(), photo.getId())).orElseThrow();

        finalization.finish(job.getId(), storyId);
        finalization.finish(job.getId(), storyId);

        assertCompletedOnce();
        assertThat(jdbc.queryForObject("SELECT processing_status FROM stories WHERE story_id = ?",
                String.class, storyId)).isEqualTo("COMPLETED");
        var captor = ArgumentCaptor.forClass(WebSocketEvent.class);
        verify(publisher).publish(captor.capture());
        verifyNoMoreInteractions(publisher);
        var data = new tools.jackson.databind.ObjectMapper()
                .valueToTree(captor.getValue().payload()).path("data");
        assertThat(data.path("eventType").asString()).isEqualTo("STORY_GENERATION_COMPLETED");
        assertThat(data.path("statusVersion").asLong()).isEqualTo(2);
        assertThat(data.path("listItem").path("sequence").asLong()).isEqualTo(1);
    }

    @Test
    void 최종_완료를_롤백하면_발행하지_않고_재시도할_수_있다() {
        Long storyId = preparation.prepare(job.getId(),
                completed(job.getExecutionId(), photo.getId())).orElseThrow();

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            finalization.finish(job.getId(), storyId);
            verifyNoInteractions(publisher);
            transaction.setRollbackOnly();
        });

        assertPreparedStoryUnpublished(storyId);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(StoryGenerationJob.Status.QUEUED);
        verifyNoInteractions(publisher);
        finalization.finish(job.getId(), storyId);
        assertCompletedOnce();
    }

    @Test
    void 취소된_준비_스토리를_최종_완료하지_않는다() {
        Long storyId = preparation.prepare(job.getId(),
                completed(job.getExecutionId(), photo.getId())).orElseThrow();
        terminal.confirmCancellation(owner.getUserId(), job.getId());
        clearInvocations(publisher);

        finalization.finish(job.getId(), storyId);

        assertPreparedStoryUnpublished(storyId);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(StoryGenerationJob.Status.CANCELED);
        assertDiscarded(storyId);
        verifyNoInteractions(publisher);
    }

    @Test
    void 준비된_스토리는_기한이_지나면_최종_완료_대신_만료시킨다() {
        Long storyId = preparation.prepare(job.getId(),
                completed(job.getExecutionId(), photo.getId())).orElseThrow();
        jdbc.update("""
                UPDATE story_generation_outbox
                SET published_at = CURRENT_TIMESTAMP(6) - INTERVAL 2 HOUR
                WHERE generation_id = ?
                """, job.getId());

        finalization.finish(job.getId(), storyId);

        assertPreparedStoryUnpublished(storyId);
        var saved = jobs.findById(job.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(StoryGenerationJob.Status.FAILED);
        assertThat(saved.getErrorCode()).isEqualTo("STORY_GENERATION_TIMEOUT");
        assertDiscarded(storyId);
    }

    @Test
    void 다른_준비_스토리의_최종_완료를_거부한다() {
        Long storyId = preparation.prepare(job.getId(),
                completed(job.getExecutionId(), photo.getId())).orElseThrow();

        assertThatThrownBy(() -> finalization.finish(job.getId(), storyId + 1))
                .isInstanceOf(IllegalStateException.class);

        assertPreparedStoryUnpublished(storyId);
        verifyNoInteractions(publisher);
    }

    @Test
    void 삭제된_준비_스토리의_최종_완료를_거부한다() {
        Long storyId = preparation.prepare(job.getId(),
                completed(job.getExecutionId(), photo.getId())).orElseThrow();
        jdbc.update("UPDATE stories SET deleted_at = CURRENT_TIMESTAMP(6) WHERE story_id = ?", storyId);

        assertThatThrownBy(() -> finalization.finish(job.getId(), storyId))
                .isInstanceOf(IllegalStateException.class);

        assertPreparedStoryUnpublished(storyId);
        verifyNoInteractions(publisher);
    }

    private void assertPreparedStoryUnpublished(Long storyId) {
        assertThat(jdbc.queryForObject("SELECT processing_status FROM stories WHERE story_id = ?",
                String.class, storyId)).isEqualTo("PROCESSING");
        assertThat(trips.findById(trip.getId()).orElseThrow().getCurrentStoryId()).isNull();
        assertThat(jdbc.queryForObject("SELECT change_sequence FROM trips WHERE trip_id = ?",
                Long.class, trip.getId())).isZero();
    }

    @Test
    void 완료_발행_전에_Mongo_내용을_저장한다() {
        doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(contentStorage).insertIfAbsent(any());

        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));

        Long storyId = jobs.findById(job.getId()).orElseThrow().getStoryId();
        var document = contents.findById(storyId).orElseThrow();
        assertThat(document.getStorySummary()).isEqualTo("여행 이야기");
        assertThat(document.getBlocks()).hasSize(1);
        assertThat(document.getBlocks().getFirst().tripAttachmentId()).isEqualTo(photo.getId());
        assertCompletedOnce();
        verify(publisher).publish(any());
    }

    @Test
    void Mongo_실패는_같은_준비_스토리로_재시도한다() {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("Mongo unavailable"))
                .doCallRealMethod().when(contentStorage).insertIfAbsent(any());

        assertThatThrownBy(() -> service.complete(job.getId(),
                completed(job.getExecutionId(), photo.getId())))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);

        Long storyId = jobs.findById(job.getId()).orElseThrow().getStoryId();
        assertPreparedStoryUnpublished(storyId);
        assertThat(contents.existsById(storyId)).isFalse();
        assertThat(deletedAt(storyId)).isNull();
        verifyNoInteractions(publisher);

        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));

        assertCompletedOnce();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStoryId()).isEqualTo(storyId);
        assertThat(contents.existsById(storyId)).isTrue();
    }

    @Test
    void Mongo_저장_후_취소되면_완료를_막는다() {
        doAnswer(invocation -> {
            Object inserted = invocation.callRealMethod();
            terminal.confirmCancellation(owner.getUserId(), job.getId());
            clearInvocations(publisher);
            return inserted;
        }).when(contentStorage).insertIfAbsent(any());

        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));

        var saved = jobs.findById(job.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(StoryGenerationJob.Status.CANCELED);
        assertThat(contents.existsById(saved.getStoryId())).isTrue();
        assertPreparedStoryUnpublished(saved.getStoryId());
        assertDiscarded(saved.getStoryId());
        verifyNoInteractions(publisher);
    }

    @Test
    void 최종_완료_실패는_Mongo_내용을_덮어쓰지_않고_재시도한다() {
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("MySQL completion unavailable"))
                .doCallRealMethod().when(finalization).finish(anyLong(), anyLong());

        assertThatThrownBy(() -> service.complete(job.getId(),
                completed(job.getExecutionId(), photo.getId())))
                .isInstanceOf(org.springframework.dao.TransientDataAccessResourceException.class);

        Long storyId = jobs.findById(job.getId()).orElseThrow().getStoryId();
        var firstDocument = contents.findById(storyId).orElseThrow();
        assertPreparedStoryUnpublished(storyId);
        assertThat(deletedAt(storyId)).isNull();
        verifyNoInteractions(publisher);

        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));

        assertCompletedOnce();
        var retriedDocument = contents.findById(storyId).orElseThrow();
        assertThat(retriedDocument.getVersion()).isEqualTo(firstDocument.getVersion());
        assertThat(retriedDocument.getBlocks()).isEqualTo(firstDocument.getBlocks());
        verify(publisher).publish(any());
    }

    @ParameterizedTest
    @EnumSource(value = StoryGenerationAiResponse.Status.class, names = {"FAILED", "CANCELED"})
    void AI_종료_결과는_준비된_스토리를_폐기한다(StoryGenerationAiResponse.Status state) {
        Long storyId = preparation.prepare(job.getId(),
                completed(job.getExecutionId(), photo.getId())).orElseThrow();
        var error = state == StoryGenerationAiResponse.Status.FAILED
                ? new StoryGenerationAiResponse.Failure("AI_FAILURE", "failure", null) : null;
        var response = new StoryGenerationAiResponse(trip.getId(), job.getExecutionId(),
                state, null, null, null, error);

        terminal.apply(job.getId(), response);
        var firstDeletedAt = deletedAt(storyId);
        terminal.apply(job.getId(), response);

        assertDiscarded(storyId);
        assertThat(deletedAt(storyId)).isEqualTo(firstDeletedAt);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus().name())
                .isEqualTo(state.name());
    }

    @Test
    void 최종_결과_복구는_준비된_스토리를_폐기한다() {
        Long storyId = preparation.prepare(job.getId(),
                completed(job.getExecutionId(), photo.getId())).orElseThrow();
        String message = """
                {"type":"story_result","trip_id":%d,"execution_id":"%s"}
                """.formatted(trip.getId(), job.getExecutionId());

        recovery.failIdentifiedJob(message);

        assertDiscarded(storyId);
        assertThat(jobs.findById(job.getId()).orElseThrow().getErrorCode())
                .isEqualTo("STORY_RESULT_PROCESSING_FAILED");
    }

    @Test
    void 취소를_롤백하면_폐기_표시도_롤백한다() {
        Long storyId = preparation.prepare(job.getId(),
                completed(job.getExecutionId(), photo.getId())).orElseThrow();

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            terminal.confirmCancellation(owner.getUserId(), job.getId());
            transaction.setRollbackOnly();
        });

        assertThat(deletedAt(storyId)).isNull();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(StoryGenerationJob.Status.QUEUED);
        verifyNoInteractions(publisher);
    }

    @Test
    void 폐기는_완료된_스토리를_변경하지_않는다() {
        service.complete(job.getId(), completed(job.getExecutionId(), photo.getId()));
        var saved = jobs.findById(job.getId()).orElseThrow();

        discard.discardPreparedStory(saved);

        assertThat(deletedAt(saved.getStoryId())).isNull();
        assertCompletedOnce();
    }

    private LocalDateTime deletedAt(Long storyId) {
        return jdbc.queryForObject("SELECT deleted_at FROM stories WHERE story_id = ?",
                LocalDateTime.class, storyId);
    }

    private void assertDiscarded(Long storyId) {
        assertThat(deletedAt(storyId)).isNotNull();
        assertThat(trips.findById(trip.getId()).orElseThrow().getCurrentStoryId()).isNull();
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
