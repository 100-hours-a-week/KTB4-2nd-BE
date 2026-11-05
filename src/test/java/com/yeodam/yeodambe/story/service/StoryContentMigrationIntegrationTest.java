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
    private Long storyId;
    private Long tripId;
    private Long placeId;
    private List<Long> orderedBlockIds;

    @BeforeEach
    void prepare() {
        var user = new User(UUID.randomUUID() + "@migration.test", "이관");
        em.persist(user);
        var trip = new Trip(user.getUserId(), "여행", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3));
        em.persist(trip);
        tripId = trip.getId();
        var time = LocalDateTime.of(2026, 10, 1, 12, 0);
        var place = TripDetailPlace.fromAnalysis(tripId, 1, "장소", BigDecimal.ZERO, BigDecimal.ZERO,
                time, time, null);
        em.persist(place);
        placeId = place.getId();
        var file = StoredFile.uploaded(user.getUserId(), "photo.jpg", UUID.randomUUID().toString(), "image/jpeg");
        em.persist(file);
        var photo = TripAttachment.initial(tripId, file.getId(), "analysis", "preview");
        em.persist(photo);
        var story = new Story(tripId, Story.Mood.PLAIN, "이관 본문");
        story.complete();
        em.persist(story);
        storyId = story.getId();
        trip.changeCurrentStory(storyId);
        em.persist(new StorySelectedPlace(storyId, placeId));
        var later = block(photo.getId(), 2);
        var first = block(addPhoto(user.getUserId()), 1);
        var deleted = block(addPhoto(user.getUserId()), 3);
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
        if (storyId != null) contents.deleteById(storyId);
    }

    @Test
    void migratesOrderedBlocksDeletionHistoryAndUtcTimestamps() {
        service.migrateOne(storyId);
        var saved = contents.findById(storyId).orElseThrow();

        assertThat(saved.getStorySummary()).isEqualTo("이관 본문");
        assertThat(saved.getMood()).isEqualTo(Story.Mood.PLAIN);
        assertThat(saved.getSelectedPlaceIds()).containsExactly(placeId);
        assertThat(saved.getBlocks()).extracting(StoryContentBlock::id).containsExactlyElementsOf(orderedBlockIds);
        assertThat(saved.getNextBlockId()).isEqualTo(orderedBlockIds.stream().mapToLong(Long::longValue).max().orElseThrow() + 1);
        assertThat(saved.getVersion()).isZero();
        var first = saved.getBlocks().getFirst();
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
    void rerunningDoesNotChangeDocumentOrVersion() {
        service.migrateOne(storyId);
        var first = contents.findById(storyId).orElseThrow();
        service.migrateOne(storyId);
        var repeated = contents.findById(storyId).orElseThrow();
        assertThat(repeated.getBlocks()).isEqualTo(first.getBlocks());
        assertThat(repeated.getVersion()).isEqualTo(first.getVersion());
        assertThat(contents.count()).isEqualTo(1);
    }

    @Test
    void preservesExistingEditedMongoContent() {
        var edited = contents.insert(new StoryContentDocument(storyId, Story.Mood.CALM,
                "Mongo 최신 본문", List.of(placeId), List.of(), 100L));
        edited.updateContent(Story.Mood.CALM, "수정된 최신 본문", List.of());
        edited = contents.save(edited);

        service.migrateOne(storyId);

        var saved = contents.findById(storyId).orElseThrow();
        assertThat(saved.getStorySummary()).isEqualTo("수정된 최신 본문");
        assertThat(saved.getMood()).isEqualTo(Story.Mood.CALM);
        assertThat(saved.getBlocks()).isEmpty();
        assertThat(saved.getNextBlockId()).isEqualTo(100L);
        assertThat(saved.getVersion()).isEqualTo(edited.getVersion());
    }

    @Test
    void targetQueryUsesAscendingIdsCursorAndPageLimit() {
        var current = em.find(Story.class, storyId);
        var history = new Story(tripId, Story.Mood.PLAIN, "과거 스토리");
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
    void targetQueryExcludesProcessingStoryAndDeletedTrip() {
        jdbc.update("UPDATE stories SET processing_status = 'PROCESSING' WHERE story_id = ?", storyId);
        assertThat(stories.findContentMigrationTargetIds(0L, PageRequest.of(0, 100))).isEmpty();
        jdbc.update("UPDATE stories SET processing_status = 'COMPLETED' WHERE story_id = ?", storyId);
        jdbc.update("UPDATE trips SET deleted_at = CURRENT_TIMESTAMP(6) WHERE trip_id = ?", tripId);
        assertThat(stories.findContentMigrationTargetIds(0L, PageRequest.of(0, 100))).isEmpty();
    }

    @Test
    void invalidExistingVersionZeroIsRejectedOnEveryAttemptWithoutOverwrite() {
        contents.insert(new StoryContentDocument(storyId, Story.Mood.PLAIN,
                "불일치 본문", List.of(placeId), List.of(), 1L));

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> service.migrateOne(storyId))
                    .isInstanceOf(StoryDataIntegrityException.class)
                    .hasMessage(StoryGenerationInternalErrorMessage.MIGRATION_CONTENT_MISMATCH.message());
        }

        var saved = contents.findById(storyId).orElseThrow();
        assertThat(saved.getStorySummary()).isEqualTo("불일치 본문");
        assertThat(saved.getBlocks()).isEmpty();
        assertThat(saved.getVersion()).isZero();
    }

    private Long addPhoto(Long userId) {
        var file = StoredFile.uploaded(userId, "photo.jpg", UUID.randomUUID().toString(), "image/jpeg");
        em.persist(file);
        var photo = TripAttachment.initial(tripId, file.getId(), "analysis", "preview");
        em.persist(photo);
        return photo.getId();
    }

    private StoryBlock block(Long photoId, int order) {
        var block = new StoryBlock(storyId, photoId, placeId, LocalDate.of(2026, 10, 1),
                order, "라벨" + order, "문구" + order, "메모" + order);
        em.persist(block);
        return block;
    }
}
