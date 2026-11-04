package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.entity.Story;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class StoryReadMongoIntegrationTest {
    @Autowired private StoryReadService service;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private StoryRepository stories;
    @Autowired private StoryContentRepository contents;
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
        if (storyId != null) contents.deleteById(storyId);
    }

    @Test
    void readsMongoBodyWithoutMysqlBlocksAndPreservesSnapshotDate() {
        contents.insert(new StoryContentDocument(storyId, Story.Mood.CALM, "MongoDB 요약", List.of(placeId),
                List.of(new StoryContentBlock(91L, photoId, placeId, LocalDate.of(2026, 10, 3),
                        "셋째 날", "문서 본문", "메모", null, null, null)), 92L));
        when(storage.createReadUrl("preview/mongo-read")).thenReturn("https://image.test/photo");

        var response = service.findStory(userId, tripId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_blocks WHERE story_id = ?",
                Integer.class, storyId)).isZero();
        assertThat(response.storyId()).isEqualTo(storyId);
        assertThat(response.mood()).isEqualTo(Story.Mood.CALM);
        assertThat(response.storySummary()).isEqualTo("MongoDB 요약");
        assertThat(response.days()).hasSize(1);
        assertThat(response.days().getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 3));
        var block = response.days().getFirst().blocks().getFirst();
        assertThat(block.storyBlockId()).isEqualTo(91L);
        assertThat(block.tripAttachmentId()).isEqualTo(photoId);
        assertThat(block.tripPlaceId()).isEqualTo(placeId);
        assertThat(block.detailSummary()).isEqualTo("문서 본문");
        assertThat(block.thumbnailUrl()).isEqualTo("https://image.test/photo");
    }

    @Test
    void missingMongoDocumentIsNotReplacedWithMysqlBody() {
        assertThatThrownBy(() -> service.findStory(userId, tripId))
                .isInstanceOf(StoryDataIntegrityException.class)
                .hasMessage("완료된 스토리의 MongoDB 본문이 없습니다.");
        verifyNoInteractions(storage);
    }
}
