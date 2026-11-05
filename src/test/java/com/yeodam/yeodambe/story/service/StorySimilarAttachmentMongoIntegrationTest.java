package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.StoryBlockNotFoundException;
import com.yeodam.yeodambe.story.client.StorySimilarAttachmentAiClient;
import com.yeodam.yeodambe.story.client.request.StorySimilarAttachmentAiRequest;
import com.yeodam.yeodambe.story.client.response.StorySimilarAttachmentAiResponse;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import com.yeodam.yeodambe.story.repository.StoryRepository;
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
class StorySimilarAttachmentMongoIntegrationTest {
    @Autowired private StorySimilarAttachmentService service;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private StoryRepository stories;
    @Autowired private StoryContentRepository contents;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private TripDetailPlaceRepository places;
    @Autowired private StoredFileRepository files;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private StorySimilarAttachmentAiClient ai;

    private Long userId;
    private Long tripId;
    private Long storyId;
    private Long photoId;
    private Long placeId;

    @BeforeEach
    void prepare() {
        User owner = users.save(new User(UUID.randomUUID() + "@mongo-similar.test", "조회"));
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
    void Mongo블록으로_추천하고_MySQL블록은_필요하지않다() {
        insertContent();
        var time = LocalDateTime.of(2026, 10, 20, 12, 0);
        var file = files.save(StoredFile.uploaded(userId, "candidate.jpg",
                UUID.randomUUID().toString(), "image/jpeg"));
        var candidate = TripAttachment.initial(tripId, file.getId(), "analysis/candidate", "preview/candidate");
        candidate.classify(placeId, RegionOrigin.EXIF, time, BigDecimal.ZERO, BigDecimal.ZERO, 70);
        var candidateId = attachments.saveAndFlush(candidate).getId();
        entityManager.clear();
        var request = new StorySimilarAttachmentAiRequest(photoId, List.of(candidateId), 10);
        when(ai.findSimilar(tripId, request)).thenReturn(new StorySimilarAttachmentAiResponse(
                photoId, List.of(new StorySimilarAttachmentAiResponse.Item(candidateId, 0.95))));

        var response = service.findSimilar(userId, storyId, 91L);

        assertThat(response.sourceTripAttachmentId()).isEqualTo(photoId);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().tripAttachmentId()).isEqualTo(candidateId);
        verify(ai).findSimilar(tripId, request);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_blocks WHERE story_id = ?",
                Integer.class, storyId)).isZero();
        assertThat(contents.findById(storyId).orElseThrow().getVersion()).isZero();
    }

    @Test
    void 타인과_과거스토리는_접근할수없다() {
        insertContent();
        assertThatThrownBy(() -> service.findSimilar(-1L, storyId, 91L))
                .isInstanceOf(StoryBlockNotFoundException.class);
        jdbc.update("UPDATE trips SET current_story_id = NULL WHERE trip_id = ?", tripId);
        entityManager.clear();
        assertThatThrownBy(() -> service.findSimilar(userId, storyId, 91L))
                .isInstanceOf(StoryBlockNotFoundException.class);
        verifyNoInteractions(ai);
    }

    @Test
    void 여행이_PROCESSING이어도_기존_조회허용조건을_유지한다() {
        insertContent();
        jdbc.update("UPDATE trips SET processing_status = 'PROCESSING' WHERE trip_id = ?", tripId);
        entityManager.clear();

        var response = service.findSimilar(userId, storyId, 91L);

        assertThat(response.sourceTripAttachmentId()).isEqualTo(photoId);
        assertThat(response.items()).isEmpty();
        verifyNoInteractions(ai);
    }

    private void insertContent() {
        contents.insert(new StoryContentDocument(storyId, Story.Mood.PLAIN, "Mongo 요약", List.of(placeId),
                List.of(new StoryContentBlock(91L, photoId, placeId, LocalDate.of(2026, 10, 3),
                        "셋째 날", "본문", null, null, null, null)), 92L));
    }
}
