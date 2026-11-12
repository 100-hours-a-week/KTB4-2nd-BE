package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.SharedTestMongo;
import com.mongodb.ConnectionString;
import com.yeodam.yeodambe.common.exception.StoryAlreadyExistsException;
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
import com.yeodam.yeodambe.story.repository.StoryGenerationOutboxRepository;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
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

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, StoryNativeGenerationIntegrationTest.MongoConfig.class})
class StoryNativeGenerationIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class MongoConfig {
        @Bean
        @Primary
        MongoConnectionDetails nativeMongo() {
            return () -> new ConnectionString(SharedTestMongo.connectionString("story_native_generation_test"));
        }
    }

    @Autowired private StoryGenerationAcceptanceService acceptance;
    @Autowired private StoryGenerationPreparationService preparation;
    @Autowired private StoryGenerationCompletionService completion;
    @Autowired private StoryGenerationTerminalService terminal;
    @MockitoSpyBean private StoryContentStorageService storage;
    @MockitoSpyBean private StoryGenerationFinalizationService finalization;
    @Autowired private StoryContentRepository contents;
    @Autowired private StoryGenerationJobRepository jobs;
    @Autowired private StoryGenerationOutboxRepository outboxes;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripDetailPlaceRepository places;
    @Autowired private TripAttachmentRepository photos;
    @Autowired private StoredFileRepository files;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private WebSocketEventPublisher publisher;

    @Value("${app.story.mongo-native-generation-enabled}")
    private boolean configuredNativeGenerationEnabled;

    private User owner;
    private Trip trip;
    private TripDetailPlace place;
    private TripAttachment photo;
    private TripAttachment secondPhoto;
    private StoryGenerationJob job;
    private List<Long> fileIds;

    @BeforeEach
    void prepare() {
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
        ReflectionTestUtils.setField(preparation, "nativeGenerationEnabled", configuredNativeGenerationEnabled);
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
    void 기본설정은_SQL블록없이_최초날짜와_결과해시를_준비한다() {
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
    void 기본설정의_신규스토리는_SQL블록없이_Mongo본문으로_완료한다() {
        completion.complete(job.getId(), result("요약"));

        StoryGenerationJob completed = jobs.findById(job.getId()).orElseThrow();
        StoryContentDocument content = contents.findById(completed.getStoryId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(StoryGenerationJob.Status.COMPLETED);
        assertThat(sqlBlockCount(content.getId())).isZero();
        assertThat(content.getStorySummary()).isEqualTo("요약");
        assertThat(content.getBlocks()).hasSize(1);
        assertThat(content.getBlocks().getFirst().tripAttachmentId()).isEqualTo(photo.getId());
        assertThat(content.getInitialContentHash()).matches("[0-9a-f]{64}");
        assertThat(completed.getPreparedContentMetadata()).contains(content.getInitialContentHash());
        assertThat(trips.findById(trip.getId()).orElseThrow().getCurrentStoryId()).isEqualTo(content.getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 기존_스토리의_모든_사진이나_파일이_삭제되면_새_생성을_접수한다(boolean deleteAttachment) {
        completion.complete(job.getId(), result("기존 요약"));
        Long previousStoryId = jobs.findById(job.getId()).orElseThrow().getStoryId();
        if (deleteAttachment) {
            jdbc.update("UPDATE trip_attachments SET deleted_at = NOW() WHERE trip_attachment_id = ?", photo.getId());
        } else {
            jdbc.update("UPDATE files SET deleted_at = NOW() WHERE file_id = ?", photo.getFileId());
        }

        StoryGenerationJob replacement = acceptance.accept(owner.getUserId(), trip.getId(),
                new StoryGenerationRequest(Story.Mood.PLAIN, List.of(place.getId())), "recover-empty-story");

        assertThat(replacement.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
        assertThat(outboxes.findByGenerationId(replacement.getId())).isPresent();
        assertThat(trips.findById(trip.getId()).orElseThrow().getCurrentStoryId()).isNull();
        assertThat(jdbc.queryForMap("SELECT deleted_at FROM stories WHERE story_id = ?", previousStoryId)
                .get("deleted_at")).isNotNull();
        assertThat(contents.existsById(previousStoryId)).isTrue();
    }

    @Test
    void 기존_스토리에_사진이_한_장이라도_남으면_새_생성을_거절한다() {
        completion.complete(job.getId(), result("기존 요약", List.of(
                aiBlock(1, photo.getId()), aiBlock(2, secondPhoto.getId()))));
        Long previousStoryId = jobs.findById(job.getId()).orElseThrow().getStoryId();
        jdbc.update("UPDATE trip_attachments SET deleted_at = NOW() WHERE trip_attachment_id = ?", photo.getId());

        assertThatThrownBy(() -> acceptance.accept(owner.getUserId(), trip.getId(),
                new StoryGenerationRequest(Story.Mood.PLAIN, List.of(place.getId())), "keep-current-story"))
                .isInstanceOf(StoryAlreadyExistsException.class);
        assertThat(trips.findById(trip.getId()).orElseThrow().getCurrentStoryId()).isEqualTo(previousStoryId);
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
    void Mongo저장후_시간이_초과되면_늦은결과도_공개하지_않는다() {
        doAnswer(invocation -> {
            Object saved = invocation.callRealMethod();
            jdbc.update("""
                    UPDATE story_generation_outbox
                    SET published_at = CURRENT_TIMESTAMP(6) - INTERVAL 2 HOUR
                    WHERE generation_id = ?
                    """, job.getId());
            return saved;
        }).when(storage).insertPreparedIfAbsent(any());

        completion.complete(job.getId(), result("요약"));
        StoryGenerationJob expired = jobs.findById(job.getId()).orElseThrow();
        StoryContentDocument preserved = contents.findById(expired.getStoryId()).orElseThrow();
        completion.complete(job.getId(), result("늦은 결과"));

        assertThat(expired.getStatus()).isEqualTo(StoryGenerationJob.Status.FAILED);
        assertThat(expired.getErrorCode()).isEqualTo("STORY_GENERATION_TIMEOUT");
        assertThat(sqlBlockCount(expired.getStoryId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM stories WHERE story_id = ?", LocalDateTime.class, expired.getStoryId()
        )).isNotNull();
        assertThat(trips.findById(trip.getId()).orElseThrow().getCurrentStoryId()).isNull();
        assertThat(contents.findById(expired.getStoryId()).orElseThrow().getVersion()).isEqualTo(preserved.getVersion());
        assertThat(contents.findById(expired.getStoryId()).orElseThrow().getStorySummary()).isEqualTo("요약");
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
    void false설정의_legacy저장실패는_true전환후에도_SQL블록으로_복구한다() {
        ReflectionTestUtils.setField(preparation, "nativeGenerationEnabled", false);
        doThrow(new DataAccessResourceFailureException(StoryGenerationInternalErrorMessage.READ_DATA_INTEGRITY_FAILED.message()))
                .doCallRealMethod().when(storage).insertIfAbsent(any());

        assertThatThrownBy(() -> completion.complete(job.getId(), result("요약")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        StoryGenerationJob prepared = jobs.findById(job.getId()).orElseThrow();
        Long storyId = prepared.getStoryId();
        assertThat(prepared.getPreparedContentMetadata()).isNull();
        assertThat(sqlBlockCount(storyId)).isEqualTo(1);
        assertThat(contents.existsById(storyId)).isFalse();
        ReflectionTestUtils.setField(preparation, "nativeGenerationEnabled", configuredNativeGenerationEnabled);

        completion.complete(job.getId(), result("요약"));

        assertThat(jobs.findById(job.getId()).orElseThrow().getStoryId()).isEqualTo(storyId);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(StoryGenerationJob.Status.COMPLETED);
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
