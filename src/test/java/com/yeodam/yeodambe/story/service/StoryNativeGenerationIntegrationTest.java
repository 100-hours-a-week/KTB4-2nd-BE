package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.common.websocket.service.WebSocketEventPublisher;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.exception.InvalidStoryResultException;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
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
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "app.story.mongo-native-generation-enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StoryNativeGenerationIntegrationTest {
    @Autowired private StoryGenerationAcceptanceService acceptance;
    @Autowired private StoryGenerationPreparationService preparation;
    @Autowired private StoryGenerationCompletionService completion;
    @Autowired private StoryGenerationTerminalService terminal;
    @Autowired private StoryContentMigrationService migration;
    @MockitoSpyBean private StoryContentStorageService storage;
    @MockitoSpyBean private StoryGenerationFinalizationService finalization;
    @Autowired private StoryContentRepository contents;
    @Autowired private StoryGenerationJobRepository jobs;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripDetailPlaceRepository places;
    @Autowired private TripAttachmentRepository photos;
    @Autowired private StoredFileRepository files;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MongoTemplate mongo;
    @MockitoBean private WebSocketEventPublisher publisher;

    private User owner;
    private Trip trip;
    private TripDetailPlace place;
    private TripAttachment photo;
    private TripAttachment secondPhoto;
    private StoryGenerationJob job;
    private List<Long> fileIds;

    @BeforeEach
    void prepare() {
        ReflectionTestUtils.setField(preparation, "nativeGenerationEnabled", true);
        owner = users.save(new User(UUID.randomUUID() + "@native.test", "생성"));
        trip = trips.save(new Trip(owner.getUserId(), "여행", LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 12)));
        jdbc.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", trip.getId());
        LocalDateTime time = LocalDateTime.of(2026, 10, 11, 12, 0);
        place = places.save(TripDetailPlace.fromAnalysis(
                trip.getId(), 1, "장소", BigDecimal.ZERO, BigDecimal.ZERO, time, time, null));
        StoredFile firstFile = files.save(StoredFile.uploaded(owner.getUserId(), "first.jpg", "first/" + UUID.randomUUID(), "image/jpeg"));
        StoredFile secondFile = files.save(StoredFile.uploaded(owner.getUserId(), "second.jpg", "second/" + UUID.randomUUID(), "image/jpeg"));
        fileIds = List.of(firstFile.getId(), secondFile.getId());
        photo = savePhoto(firstFile);
        secondPhoto = savePhoto(secondFile);
        job = acceptance.accept(owner.getUserId(), trip.getId(),
                new StoryGenerationRequest(Story.Mood.PLAIN, List.of(place.getId())), "native-test");
        clearInvocations(publisher);
    }

    @AfterEach
    void clean() {
        ReflectionTestUtils.setField(preparation, "nativeGenerationEnabled", true);
        if (trip == null) {
            return;
        }
        jdbc.update("UPDATE trips SET current_story_id = NULL WHERE trip_id = ?", trip.getId());
        contents.deleteAllById(jdbc.queryForList("SELECT story_id FROM stories WHERE trip_id = ?", Long.class, trip.getId()));
        jdbc.update("DELETE FROM story_generation_outbox WHERE generation_id IN (SELECT generation_id FROM story_generation_jobs WHERE trip_id = ?)", trip.getId());
        jdbc.update("DELETE FROM story_generation_places WHERE generation_id IN (SELECT generation_id FROM story_generation_jobs WHERE trip_id = ?)", trip.getId());
        jdbc.update("DELETE FROM story_generation_jobs WHERE trip_id = ?", trip.getId());
        jdbc.update("DELETE FROM story_blocks WHERE story_id IN (SELECT story_id FROM stories WHERE trip_id = ?)", trip.getId());
        jdbc.update("DELETE FROM story_selected_places WHERE story_id IN (SELECT story_id FROM stories WHERE trip_id = ?)", trip.getId());
        jdbc.update("DELETE FROM stories WHERE trip_id = ?", trip.getId());
        jdbc.update("DELETE FROM trip_attachments WHERE trip_id = ?", trip.getId());
        jdbc.update("DELETE FROM trip_detail_places WHERE trip_id = ?", trip.getId());
        jdbc.update("DELETE FROM trips WHERE trip_id = ?", trip.getId());
        files.deleteAllById(fileIds);
        users.deleteById(owner.getUserId());
    }

    @Test
    void SQL블록없이_최초날짜와_결과해시를_준비한다() {
        StoryContentDocument prepared = preparation.prepareContent(job.getId(), result("요약")).orElseThrow();

        assertThat(sqlBlockCount(prepared.getId())).isZero();
        assertThat(prepared.getBlocks().getFirst().id()).isEqualTo(1L);
        assertThat(prepared.getNextBlockId()).isEqualTo(2L);
        assertThat(prepared.getBlocks().getFirst().previewStorageKey()).isEqualTo(photo.getPreviewStorageKey());
        assertThat(prepared.getInitialContentHash()).matches("[0-9a-f]{64}");
        assertThat(jobs.findById(job.getId()).orElseThrow().getPreparedContentMetadata())
                .contains(prepared.getInitialContentHash()).contains("2026-10-11");
        assertThat(trips.findById(trip.getId()).orElseThrow().getCurrentStoryId()).isNull();
    }

    @Test
    void 준비후_날짜와_전환설정이_바뀌어도_최초스냅샷으로_재개한다() {
        StoryContentDocument first = preparation.prepareContent(job.getId(), result("요약")).orElseThrow();
        jdbc.update("UPDATE trip_detail_places SET started_at = '2026-10-12 12:00:00' WHERE trip_place_id = ?", place.getId());
        ReflectionTestUtils.setField(preparation, "nativeGenerationEnabled", false);

        StoryContentDocument repeated = preparation.prepareContent(job.getId(), result("요약")).orElseThrow();

        assertThat(repeated.getId()).isEqualTo(first.getId());
        assertThat(repeated.getBlocks()).isEqualTo(first.getBlocks());
        assertThat(repeated.getInitialContentHash()).isEqualTo(first.getInitialContentHash());
        completion.complete(job.getId(), result("요약"));
        assertThat(sqlBlockCount(first.getId())).isZero();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(StoryGenerationJob.Status.COMPLETED);
    }

    @Test
    void Mongo최초저장전에도_다른결과를_같은실행으로_받지_않는다() {
        StoryContentDocument first = preparation.prepareContent(job.getId(), result("최초 요약")).orElseThrow();

        assertThatThrownBy(() -> preparation.prepareContent(job.getId(), result("다른 요약")))
                .isInstanceOf(InvalidStoryResultException.class);
        assertThat(contents.existsById(first.getId())).isFalse();
        assertThat(jobs.findById(job.getId()).orElseThrow().getPreparedContentMetadata()).contains(first.getInitialContentHash());
    }

    @Test
    void 결과배열이_달라도_순서번호로_같은_블록ID와_해시를_만든다() {
        StoryGenerationAiResponse.Block first = aiBlock(2, photo.getId());
        StoryGenerationAiResponse.Block second = aiBlock(1, secondPhoto.getId());
        StoryContentDocument prepared = preparation.prepareContent(job.getId(), result("요약", List.of(first, second))).orElseThrow();

        StoryContentDocument repeated = preparation.prepareContent(job.getId(), result("요약", List.of(second, first))).orElseThrow();

        assertThat(repeated.getBlocks()).isEqualTo(prepared.getBlocks());
        assertThat(repeated.getInitialContentHash()).isEqualTo(prepared.getInitialContentHash());
        assertThat(repeated.getBlocks().getFirst().tripAttachmentId()).isEqualTo(secondPhoto.getId());
    }

    @Test
    void Mongo저장실패는_같은스토리로_재처리한다() {
        doThrow(new DataAccessResourceFailureException(StoryGenerationInternalErrorMessage.READ_DATA_INTEGRITY_FAILED.message()))
                .doCallRealMethod().when(storage).insertPreparedIfAbsent(any());

        assertThatThrownBy(() -> completion.complete(job.getId(), result("요약")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        Long storyId = jobs.findById(job.getId()).orElseThrow().getStoryId();
        assertThat(contents.existsById(storyId)).isFalse();
        completion.complete(job.getId(), result("요약"));

        assertThat(jobs.findById(job.getId()).orElseThrow().getStoryId()).isEqualTo(storyId);
        assertThat(contents.existsById(storyId)).isTrue();
    }

    @Test
    void Mongo저장뒤_SQL완료실패는_문서를_덮어쓰지_않고_복구한다() {
        doThrow(new DataAccessResourceFailureException(StoryGenerationInternalErrorMessage.READ_DATA_INTEGRITY_FAILED.message()))
                .doCallRealMethod().when(finalization).finish(anyLong(), anyLong());
        assertThatThrownBy(() -> completion.complete(job.getId(), result("요약")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        Long storyId = jobs.findById(job.getId()).orElseThrow().getStoryId();
        StoryContentDocument first = contents.findById(storyId).orElseThrow();

        completion.complete(job.getId(), result("요약"));

        assertThat(contents.findById(storyId).orElseThrow().getVersion()).isEqualTo(first.getVersion());
        assertThat(contents.findById(storyId).orElseThrow().getBlocks()).isEqualTo(first.getBlocks());
        verify(publisher).publish(any());
    }

    @Test
    void 저장도중_취소가_확정되면_Mongo문서가_있어도_공개하지_않는다() {
        doAnswer(invocation -> {
            Object saved = invocation.callRealMethod();
            terminal.confirmCancellation(owner.getUserId(), job.getId());
            return saved;
        }).when(storage).insertPreparedIfAbsent(any());

        completion.complete(job.getId(), result("요약"));

        StoryGenerationJob saved = jobs.findById(job.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(StoryGenerationJob.Status.CANCELED);
        assertThat(contents.existsById(saved.getStoryId())).isTrue();
        assertThat(trips.findById(trip.getId()).orElseThrow().getCurrentStoryId()).isNull();
    }

    @Test
    void native완료문서는_기동검증과_키보정을_SQL블록없이_통과한다() {
        completion.complete(job.getId(), result("요약"));
        Long storyId = jobs.findById(job.getId()).orElseThrow().getStoryId();
        assertThatCode(() -> migration.migrateOne(storyId)).doesNotThrowAnyException();
        mongo.getCollection("story_contents").updateOne(new Document("_id", storyId),
                new Document("$unset", new Document("blocks.0.previewStorageKey", "")));
        assertThat(migration.backfillPreviewKeys(storyId - 1, 1).updated()).isEqualTo(1);
        contents.deleteById(storyId);

        assertThatThrownBy(() -> migration.migrateOne(storyId)).isInstanceOf(StoryDataIntegrityException.class)
                .hasMessage(StoryGenerationInternalErrorMessage.CONTENT_DOCUMENT_MISSING.message());
        assertThat(contents.existsById(storyId)).isFalse();
    }

    @Test
    void 중복저장은_편집본문을_유지하며_다른초기해시는_거절한다() {
        StoryContentDocument expected = preparation.prepareContent(job.getId(), result("요약")).orElseThrow();
        storage.insertPreparedIfAbsent(expected);
        StoryContentDocument edited = contents.findById(expected.getId()).orElseThrow();
        edited.updateContent(Story.Mood.CALM, "편집된 최신 본문", edited.getBlocks());
        contents.save(edited);

        assertThat(storage.insertPreparedIfAbsent(expected).getStorySummary()).isEqualTo("편집된 최신 본문");
        StoryContentDocument mismatched = new StoryContentDocument(
                expected.getId(), expected.getMood(), expected.getStorySummary(), expected.getSelectedPlaceIds(),
                expected.getBlocks(), expected.getNextBlockId(), "0".repeat(64)
        );
        assertThatThrownBy(() -> storage.insertPreparedIfAbsent(mismatched)).isInstanceOf(InvalidStoryResultException.class);
    }

    @Test
    void legacy준비가_끝난작업은_설정이_true여도_SQL블록으로_완료한다() {
        Long storyId = preparation.prepare(job.getId(), result("요약")).orElseThrow();

        completion.complete(job.getId(), result("요약"));

        assertThat(sqlBlockCount(storyId)).isEqualTo(1);
        assertThat(contents.findById(storyId).orElseThrow().getInitialContentHash()).isNull();
    }

    @Test
    void 동시_중복결과도_문서와_완료이벤트를_한번만_저장한다() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> completion.complete(job.getId(), result("요약")));
            Future<?> second = executor.submit(() -> completion.complete(job.getId(), result("요약")));
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        StoryGenerationJob saved = jobs.findById(job.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(StoryGenerationJob.Status.COMPLETED);
        assertThat(sqlBlockCount(saved.getStoryId())).isZero();
        verify(publisher).publish(any());
    }

    @Test
    void legacy진입점은_native준비작업을_SQL본문으로_복원하지_않는다() {
        StoryContentDocument expected = preparation.prepareContent(job.getId(), result("요약")).orElseThrow();

        assertThatThrownBy(() -> preparation.prepare(job.getId(), result("다른 요약")))
                .isInstanceOf(StoryDataIntegrityException.class);
        assertThatThrownBy(() -> preparation.readPreparedContent(
                job.getId(), expected.getId(), ZoneOffset.UTC
        )).isInstanceOf(StoryDataIntegrityException.class);
        assertThat(sqlBlockCount(expected.getId())).isZero();
    }

    @Test
    void native해시나_SQL출처가_없으면_편집버전도_이관으로_덮어쓰지_않는다() {
        completion.complete(job.getId(), result("요약"));
        Long storyId = jobs.findById(job.getId()).orElseThrow().getStoryId();
        StoryContentDocument edited = contents.findById(storyId).orElseThrow();
        edited.updateContent(Story.Mood.CALM, "편집 본문", edited.getBlocks());
        contents.save(edited);
        mongo.getCollection("story_contents").updateOne(new Document("_id", storyId),
                new Document("$unset", new Document("initialContentHash", "")));

        assertThatThrownBy(() -> migration.migrateOne(storyId)).isInstanceOf(StoryDataIntegrityException.class);
        mongo.getCollection("story_contents").updateOne(new Document("_id", storyId),
                new Document("$set", new Document("initialContentHash", edited.getInitialContentHash())));
        jdbc.update("UPDATE story_generation_jobs SET prepared_content_metadata = NULL WHERE generation_id = ?", job.getId());

        assertThatThrownBy(() -> migration.migrateOne(storyId)).isInstanceOf(StoryDataIntegrityException.class);
        assertThat(contents.findById(storyId).orElseThrow().getStorySummary()).isEqualTo("편집 본문");
    }

    private TripAttachment savePhoto(StoredFile file) {
        TripAttachment attachment = TripAttachment.initial(trip.getId(), file.getId(), "analysis", "preview/" + file.getId());
        attachment.classify(place.getId(), RegionOrigin.EXIF, place.getStartedAt(), BigDecimal.ZERO, BigDecimal.ZERO, 80);
        return photos.saveAndFlush(attachment);
    }

    private int sqlBlockCount(Long storyId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM story_blocks WHERE story_id = ?", Integer.class, storyId);
    }

    private StoryGenerationAiResponse result(String summary) {
        return result(summary, List.of(aiBlock(1, photo.getId())));
    }

    private StoryGenerationAiResponse result(String summary, List<StoryGenerationAiResponse.Block> blocks) {
        return new StoryGenerationAiResponse(
                trip.getId(), job.getExecutionId(), StoryGenerationAiResponse.Status.COMPLETED,
                new StoryGenerationAiResponse.Progress(1, 1), null,
                new StoryGenerationAiResponse.Result(summary, blocks), null
        );
    }

    private StoryGenerationAiResponse.Block aiBlock(int order, Long photoId) {
        return new StoryGenerationAiResponse.Block(order, place.getId(), photoId, "첫날", "사진 설명", null);
    }
}
