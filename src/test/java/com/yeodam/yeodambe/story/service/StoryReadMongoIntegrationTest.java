package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.service.response.StoryDetailResponse;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, StoryReadMongoIntegrationTest.MongoConfig.class, StorySqlCounterConfiguration.class})
@Transactional
class StoryReadMongoIntegrationTest {

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class MongoConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails isolatedMongo() {
            return () -> new com.mongodb.ConnectionString(
                    com.yeodam.yeodambe.SharedTestMongo.connectionString("storyreadmongointegrationtest"));
        }
    }

    @Autowired private StoryReadService service;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private StoryRepository stories;
    @MockitoSpyBean
    private StoryContentRepository contents;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private TripDetailPlaceRepository places;
    @Autowired private StoredFileRepository files;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private TripAttachmentStorageClient storage;

    private Long userId;
    private Long tripId;
    private Long storyId;
    private Long photoId;
    private Long placeId;

    @BeforeEach
    void prepare() {
        User owner = users.save(new User(UUID.randomUUID() + "@mongo-read.test", "조회"));
        userId = owner.getUserId();
        Trip trip = trips.save(new Trip(userId, "여행", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)));
        tripId = trip.getId();
        var placeTime = LocalDateTime.of(2026, 10, 20, 12, 0);
        var place = places.save(TripDetailPlace.fromAnalysis(tripId, 1, "장소",
                BigDecimal.ZERO, BigDecimal.ZERO, placeTime, placeTime, null));
        placeId = place.getId();
        var file = files.save(StoredFile.uploaded(userId, "photo.jpg", UUID.randomUUID().toString(), "image/jpeg"));
        var photo = TripAttachment.initial(tripId, file.getId(), "analysis", "preview/mongo-read");
        photo.classify(placeId, RegionOrigin.EXIF, placeTime, BigDecimal.ZERO, BigDecimal.ZERO, 80);
        photoId = attachments.save(photo).getId();
        Story story = new Story(tripId, Story.Mood.PLAIN, "MySQL 요약");
        story.complete();
        storyId = stories.saveAndFlush(story).getId();
        trip.changeCurrentStory(storyId);
        entityManager.flush();
        jdbc.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", tripId);
        entityManager.clear();
    }

    @AfterEach
    void cleanMongo() {
        if (storyId != null) {
            contents.deleteById(storyId);
        }
    }

    @Test
    void SQL블록없이_Mongo본문과_원본날짜를_조회한다() {
        contents.insert(new StoryContentDocument(storyId, Story.Mood.CALM, "MongoDB 요약", List.of(placeId),
                List.of(new StoryContentBlock(91L, photoId, placeId, LocalDate.of(2026, 10, 3),
                        "셋째 날", "문서 본문", "메모", null, null, null)), 92L));
        when(storage.createReadUrl("preview/mongo-read")).thenReturn("https://image.test/photo");

        StoryDetailResponse response = service.findStory(userId, tripId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_blocks WHERE story_id = ?",
                Integer.class, storyId)).isZero();
        assertThat(response.storyId()).isEqualTo(storyId);
        assertThat(response.mood()).isEqualTo(Story.Mood.CALM);
        assertThat(response.storySummary()).isEqualTo("MongoDB 요약");
        assertThat(response.days()).hasSize(1);
        assertThat(response.days().getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 3));
        StoryDetailResponse.Block block = response.days().getFirst().blocks().getFirst();
        assertThat(block.storyBlockId()).isEqualTo(91L);
        assertThat(block.tripAttachmentId()).isEqualTo(photoId);
        assertThat(block.tripPlaceId()).isEqualTo(placeId);
        assertThat(block.detailSummary()).isEqualTo("문서 본문");
        assertThat(block.thumbnailUrl()).isEqualTo("https://image.test/photo");
    }

    @Test
    void 누락된_Mongo본문을_SQL로_대체하지_않는다() {
        assertThatThrownBy(() -> service.findStory(userId, tripId))
                .isInstanceOf(StoryDataIntegrityException.class)
                .hasMessage("완료된 스토리의 MongoDB 본문이 없습니다.");
        verifyNoInteractions(storage);
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 정상사진_GET은_소유자2회_공유자3회_SQL을_사용한다(boolean shared) {
        Long actorId = userId;
        if (shared) {
            User reader = users.save(new User(UUID.randomUUID() + "@shared-count.test", "공유"));
            actorId = reader.getUserId();
            LocalDateTime now = LocalDateTime.now();
            entityManager.persist(new TripShareUser(
                    tripId, userId, actorId, TripSharePermission.READ, now.plusDays(1), now
            ));
        }
        contents.insert(new StoryContentDocument(
                storyId, Story.Mood.PLAIN, "계측 요약", List.of(placeId),
                List.of(new StoryContentBlock(91L, photoId, placeId, LocalDate.of(2026, 10, 3),
                        "날", "문구", null, null, null, null)), 92L
        ));
        entityManager.flush();
        entityManager.clear();
        clearInvocations(contents);
        when(storage.createReadUrl("preview/mongo-read")).thenReturn("signed-url");
        StorySqlCounterConfiguration.begin();
        try {
            service.findStory(actorId, tripId);
            List<String> queries = StorySqlCounterConfiguration.finish();

            assertThat(queries).hasSize(shared ? 3 : 2);
            assertThat(queries).anyMatch(sql -> sql.contains("JSON_TABLE"));
            assertThat(queries).noneMatch(sql -> sql.contains("story_blocks"));
            verify(contents).findById(storyId);
            verify(contents, never()).save(any());
        } finally {
            StorySqlCounterConfiguration.clear();
        }
    }

}
