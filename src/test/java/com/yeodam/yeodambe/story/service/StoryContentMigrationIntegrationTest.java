package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.SharedTestMongo;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.entity.*;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, StoryContentMigrationIntegrationTest.MongoConfig.class})
@Transactional
class StoryContentMigrationIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class MongoConfig {
        @Bean
        @Primary
        MongoConnectionDetails isolatedMongo() {
            return () -> new com.mongodb.ConnectionString(
                    SharedTestMongo.connectionString("story_content_migration_test"));
        }
    }

    @Autowired StoryContentMigrationService service;
    @Autowired StoryRepository stories;
    @Autowired StoryContentRepository contents;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.data.mongodb.core.MongoTemplate mongo;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.yeodam.yeodambe.story.repository.StoryBlockReferenceQuery referenceQuery;
    private Long storyId;
    private Long tripId;
    private Long placeId;
    private List<Long> orderedBlockIds;

    @BeforeEach
    void prepare() {
        User user = new User(UUID.randomUUID() + "@migration.test", "이관");
        em.persist(user);
        Trip trip = new Trip(user.getUserId(), "여행", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3));
        em.persist(trip);
        tripId = trip.getId();
        LocalDateTime time = LocalDateTime.of(2026, 10, 1, 12, 0);
        TripDetailPlace place = TripDetailPlace.fromAnalysis(tripId, 1, "장소", BigDecimal.ZERO, BigDecimal.ZERO,
                time, time, null);
        em.persist(place);
        placeId = place.getId();
        StoredFile file = StoredFile.uploaded(user.getUserId(), "photo.jpg", UUID.randomUUID().toString(), "image/jpeg");
        em.persist(file);
        TripAttachment photo = TripAttachment.initial(tripId, file.getId(), "analysis", "preview");
        em.persist(photo);
        Story story = new Story(tripId, Story.Mood.PLAIN, "이관 본문");
        story.complete();
        em.persist(story);
        storyId = story.getId();
        trip.changeCurrentStory(storyId);
        em.persist(new StorySelectedPlace(storyId, placeId));
        StoryBlock later = block(photo.getId(), 2);
        StoryBlock first = block(addPhoto(user.getUserId()), 1);
        StoryBlock deleted = block(addPhoto(user.getUserId()), 3);
        orderedBlockIds = List.of(first.getId(), later.getId(), deleted.getId());
        em.flush();
        jdbc.update("UPDATE story_blocks SET created_at = '2026-10-01 01:02:03.123456', "
                + "updated_at = '2026-10-02 02:03:04.654321' WHERE story_id = ?", storyId);
        jdbc.update("UPDATE story_blocks SET deleted_at = '2026-10-03 03:04:05.987654' "
                + "WHERE story_block_id = ?", deleted.getId());
        em.clear();
    }

    @AfterEach
    void cleanMongo() {
        if (storyId != null) {
            contents.deleteById(storyId);
        }
    }

    @Test
    void 블록순서와_삭제이력과_UTC시각을_이관한다() {
        service.migrateOne(storyId);
        StoryContentDocument saved = contents.findById(storyId).orElseThrow();

        assertThat(saved.getStorySummary()).isEqualTo("이관 본문");
        assertThat(saved.getMood()).isEqualTo(Story.Mood.PLAIN);
        assertThat(saved.getSelectedPlaceIds()).containsExactly(placeId);
        assertThat(saved.getBlocks()).extracting(StoryContentBlock::id).containsExactlyElementsOf(orderedBlockIds);
        assertThat(saved.getNextBlockId()).isEqualTo(orderedBlockIds.stream().mapToLong(Long::longValue).max().orElseThrow() + 1);
        assertThat(saved.getVersion()).isZero();
        StoryContentBlock first = saved.getBlocks().getFirst();
        assertThat(first.sourceTripPlaceId()).isEqualTo(placeId);
        assertThat(first.sourceDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(first.dayLabel()).isEqualTo("라벨1");
        assertThat(first.detailSummary()).isEqualTo("문구1");
        assertThat(first.memo()).isEqualTo("메모1");
        assertThat(first.createdAt()).isEqualTo(Instant.parse("2026-10-01T01:02:03.123Z"));
        assertThat(first.updatedAt()).isEqualTo(Instant.parse("2026-10-02T02:03:04.654Z"));
        assertThat(first.deletedAt()).isNull();
        assertThat(saved.getBlocks().getLast().deletedAt()).isEqualTo(Instant.parse("2026-10-03T03:04:05.987Z"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_blocks WHERE story_id = ?",
                Integer.class, storyId)).isEqualTo(3);
    }

    @Test
    void 재이관은_문서와_버전을_변경하지_않는다() {
        service.migrateOne(storyId);
        StoryContentDocument first = contents.findById(storyId).orElseThrow();
        service.migrateOne(storyId);
        StoryContentDocument repeated = contents.findById(storyId).orElseThrow();
        assertThat(repeated.getBlocks()).isEqualTo(first.getBlocks());
        assertThat(repeated.getVersion()).isEqualTo(first.getVersion());
        assertThat(contents.count()).isEqualTo(1);
    }

    @Test
    void 이미_편집한_Mongo본문을_보존한다() {
        StoryContentDocument edited = contents.insert(new StoryContentDocument(storyId, Story.Mood.CALM,
                "Mongo 최신 본문", List.of(placeId), List.of(), 100L));
        edited.updateContent(Story.Mood.CALM, "수정된 최신 본문", List.of());
        edited = contents.save(edited);

        service.migrateOne(storyId);

        StoryContentDocument saved = contents.findById(storyId).orElseThrow();
        assertThat(saved.getStorySummary()).isEqualTo("수정된 최신 본문");
        assertThat(saved.getMood()).isEqualTo(Story.Mood.CALM);
        assertThat(saved.getBlocks()).isEmpty();
        assertThat(saved.getNextBlockId()).isEqualTo(100L);
        assertThat(saved.getVersion()).isEqualTo(edited.getVersion());
    }

    @Test
    void 이관대상은_ID커서와_페이지크기를_따른다() {
        Story current = em.find(Story.class, storyId);
        Story history = new Story(tripId, Story.Mood.PLAIN, "과거 스토리");
        history.complete();
        em.persist(history);
        em.flush();
        assertThat(stories.findContentMigrationTargetIds(0L, PageRequest.of(0, 1)))
                .containsExactly(storyId);
        assertThat(stories.findContentMigrationTargetIds(storyId, PageRequest.of(0, 1))).isEmpty();

        current.softDelete(LocalDateTime.now());
        em.flush();
        assertThat(stories.findContentMigrationTargetIds(0L, PageRequest.of(0, 100))).isEmpty();
    }

    @Test
    void 처리중스토리와_삭제여행은_이관에서_제외한다() {
        jdbc.update("UPDATE stories SET processing_status = 'PROCESSING' WHERE story_id = ?", storyId);
        assertThat(stories.findContentMigrationTargetIds(0L, PageRequest.of(0, 100))).isEmpty();
        jdbc.update("UPDATE stories SET processing_status = 'COMPLETED' WHERE story_id = ?", storyId);
        jdbc.update("UPDATE trips SET deleted_at = CURRENT_TIMESTAMP(6) WHERE trip_id = ?", tripId);
        assertThat(stories.findContentMigrationTargetIds(0L, PageRequest.of(0, 100))).isEmpty();
    }

    @Test
    void 버전0_불일치문서는_재시도해도_덮어쓰지_않는다() {
        contents.insert(new StoryContentDocument(storyId, Story.Mood.PLAIN,
                "불일치 본문", List.of(placeId), List.of(), 1L));

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> service.migrateOne(storyId))
                    .isInstanceOf(StoryDataIntegrityException.class)
                    .hasMessage(StoryGenerationInternalErrorMessage.MIGRATION_CONTENT_MISMATCH.message());
        }

        StoryContentDocument saved = contents.findById(storyId).orElseThrow();
        assertThat(saved.getStorySummary()).isEqualTo("불일치 본문");
        assertThat(saved.getBlocks()).isEmpty();
        assertThat(saved.getVersion()).isZero();
    }

    private Long addPhoto(Long userId) {
        StoredFile file = StoredFile.uploaded(userId, "photo.jpg", UUID.randomUUID().toString(), "image/jpeg");
        em.persist(file);
        TripAttachment photo = TripAttachment.initial(tripId, file.getId(), "analysis", "preview");
        em.persist(photo);
        return photo.getId();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "null", "zero"})
    void 키보정은_본문과_삭제이력을_보존하고_누락버전도_안전하게_갱신한다(String version) {
        service.migrateOne(storyId);
        removeKeys();
        if (version.equals("missing")) {
            mongo.getCollection("story_contents").updateOne(new org.bson.Document("_id", storyId),
                    new org.bson.Document("$unset", new org.bson.Document("version", "")));
        } else if (version.equals("null")) {
            mongo.getCollection("story_contents").updateOne(new org.bson.Document("_id", storyId),
                    new org.bson.Document("$set", new org.bson.Document("version", null)));
        }
        StoryContentDocument before = contents.findById(storyId).orElseThrow();

        com.yeodam.yeodambe.story.service.response.StoryPreviewKeyBackfillBatch result =
                service.backfillPreviewKeys(0L, 100);

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.invalidStoryIds()).isEmpty();
        StoryContentDocument after = contents.findById(storyId).orElseThrow();
        assertThat(after.getBlocks().getFirst().previewStorageKey()).isNotBlank();
        assertThat(after.getBlocks().getLast().previewStorageKey()).isNull();
        assertThat(withoutKeys(after.getBlocks())).isEqualTo(before.getBlocks());
        assertThat(after.getStorySummary()).isEqualTo(before.getStorySummary());
        assertThat(after.getNextBlockId()).isEqualTo(before.getNextBlockId());
        assertThat(after.getVersion()).isEqualTo(version.equals("zero") ? 1L : 0L);
        assertThat(service.backfillPreviewKeys(0L, 100).unchanged()).isEqualTo(1);
        assertThat(contents.findById(storyId).orElseThrow().getVersion()).isEqualTo(after.getVersion());
    }

    @Test
    void 키보정후_오래된_PUT은_보정본문을_덮어쓰지_못한다() {
        service.migrateOne(storyId);
        removeKeys();
        StoryContentDocument stale = contents.findById(storyId).orElseThrow();

        assertThat(service.backfillPreviewKeys(0L, 100).updated()).isEqualTo(1);
        stale.updateContent(Story.Mood.CALM, "오래된 편집", stale.getBlocks());

        assertThatThrownBy(() -> contents.save(stale))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        assertThat(contents.findById(storyId).orElseThrow().getStorySummary()).isEqualTo("이관 본문");
    }

    @Test
    void 보정중_편집이_먼저_저장되면_충돌ID를_남기고_최신본문을_보존한다() {
        service.migrateOne(storyId);
        removeKeys();
        org.mockito.Mockito.doAnswer(invocation -> {
            Object details = invocation.callRealMethod();
            StoryContentDocument edited = contents.findById(storyId).orElseThrow();
            edited.updateContent(Story.Mood.CALM, "먼저 저장한 편집", edited.getBlocks());
            contents.save(edited);
            return details;
        }).when(referenceQuery).findByBlocks(org.mockito.ArgumentMatchers.anyList());

        com.yeodam.yeodambe.story.service.response.StoryPreviewKeyBackfillBatch result =
                service.backfillPreviewKeys(0L, 100);

        assertThat(result.updated()).isZero();
        assertThat(result.conflictStoryIds()).containsExactly(storyId);
        assertThat(contents.findById(storyId).orElseThrow().getStorySummary()).isEqualTo("먼저 저장한 편집");
    }

    @Test
    void 불일치한_미편집문서는_버전을_올려_이관검증을_우회하지_않는다() {
        contents.insert(new StoryContentDocument(storyId, Story.Mood.PLAIN,
                "불일치 본문", List.of(placeId), List.of(), 1L));

        assertThat(service.backfillPreviewKeys(0L, 100).invalidStoryIds()).containsExactly(storyId);
        assertThat(contents.findById(storyId).orElseThrow().getVersion()).isZero();
    }

    @Test
    void 보정배치의_잘못된_커서와_크기를_거절한다() {
        assertThatThrownBy(() -> service.backfillPreviewKeys(-1L, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.backfillPreviewKeys(0L, 501))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void removeKeys() {
        mongo.getCollection("story_contents").updateOne(new org.bson.Document("_id", storyId),
                new org.bson.Document("$unset", new org.bson.Document()
                        .append("blocks.0.previewStorageKey", "")
                        .append("blocks.1.previewStorageKey", "")
                        .append("blocks.2.previewStorageKey", "")));
    }

    private List<StoryContentBlock> withoutKeys(List<StoryContentBlock> blocks) {
        return blocks.stream().map(block -> new StoryContentBlock(
                block.id(), block.tripAttachmentId(), block.sourceTripPlaceId(), block.sourceDate(),
                block.dayLabel(), block.detailSummary(), block.memo(), block.createdAt(),
                block.updatedAt(), block.deletedAt()
        )).toList();
    }

    private StoryBlock block(Long photoId, int order) {
        StoryBlock block = new StoryBlock(storyId, photoId, placeId, LocalDate.of(2026, 10, 1),
                order, "라벨" + order, "문구" + order, "메모" + order);
        em.persist(block);
        return block;
    }
}
