package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.story.entity.*;
import com.yeodam.yeodambe.common.exception.StoryNotFoundException;
import com.yeodam.yeodambe.story.service.StoryReadService;
import com.yeodam.yeodambe.story.service.StoryValidator;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.service.TripAccessService;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, StoryReadService.class, StoryValidator.class, TripAccessService.class})
@ActiveProfiles("test")
class StoryRepositoryTest {
    @Autowired
    EntityManager em;

    @Autowired
    StoryRepository stories;

    @Autowired
    StoryBlockRepository blocks;

    @Autowired
    StoryReadService service;

    @MockitoBean
    TripAttachmentStorageClient storage;

    @Test
    void 실제_소유권_현재대상_조회와_삭제필터_메모보존을_통합검증한다() {
        Trip trip = trip();
        Story story = story(trip, Story.Status.COMPLETED);
        assertThatThrownBy(() -> service.findStory(trip.getUserId(), trip.getId())).isInstanceOf(StoryNotFoundException.class);
        select(trip, story);
        TripDetailPlace place = TripDetailPlace.fromAnalysis(trip.getId(), 1, "장소", BigDecimal.ZERO, BigDecimal.ZERO, LocalDateTime.of(2026, 10, 7, 12, 0), LocalDateTime.of(2026, 10, 7, 13, 0), null);
                em.persist(place);
        StoryBlock later = block(story, trip, place, 2);
        StoryBlock earlier = block(story, trip, place, 1);
        StoryBlock deleted = block(story, trip, place, 0);
        em.find(TripAttachment.class, deleted.getTripAttachmentId()).softDelete(LocalDateTime.now());
        em.flush();
        em.clear();
        when(storage.createReadUrl("미리보기")).thenReturn("https://image.test/preview");
        var response = service.findStory(trip.getUserId(), trip.getId());
        assertThat(response.days()).hasSize(1);
        assertThat(response.days().getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(response.days().getFirst().blocks()).extracting(b -> b.storyBlockId()).containsExactly(earlier.getId(), later.getId());
        assertThat(response.days().getFirst().blocks()).extracting(b -> b.memo()).containsOnly("편집 메모");
        assertThat(em.find(StoryBlock.class, earlier.getId()).getMemo()).isEqualTo("편집 메모");
        verify(storage, times(2)).createReadUrl("미리보기");
        assertThatThrownBy(() -> service.findStory(-1L, trip.getId())).isInstanceOf(StoryNotFoundException.class);
        Trip loaded = em.find(Trip.class, trip.getId());
        loaded.softDelete(LocalDateTime.now());
        em.flush();
        assertThatThrownBy(() -> service.findStory(trip.getUserId(), trip.getId())).isInstanceOf(StoryNotFoundException.class);
    }

    @Test
    void 현재_완료_대상만_조회하고_과거이력으로_대체하지_않는다() {
        Trip trip = trip();
        Story history = story(trip, Story.Status.COMPLETED);
        Story current = story(trip, Story.Status.COMPLETED);
        assertThat(stories.findCurrentCompletedByTripId(trip.getId())).isEmpty();
        select(trip, current);
        assertThat(stories.findCurrentCompletedByTripId(trip.getId())).map(Story::getId).contains(current.getId());
        for (Story.Status status : List.of(Story.Status.PROCESSING, Story.Status.FAILED, Story.Status.CANCELED)) {
            ReflectionTestUtils.setField(current, "processingStatus", status);
            em.flush();
            assertThat(stories.findCurrentCompletedByTripId(trip.getId())).isEmpty();
        }
        ReflectionTestUtils.setField(current, "processingStatus", Story.Status.COMPLETED);
        current.softDelete(LocalDateTime.now());
        em.flush();
        assertThat(stories.findCurrentCompletedByTripId(trip.getId())).isEmpty();
        Trip other = trip();
        select(other, history);
        assertThat(stories.findCurrentCompletedByTripId(other.getId())).isEmpty();
    }

    @Test
    void 미삭제_블록을_동률_ID순으로_한번에_조회하며_삭제사진과_없는장소도_남긴다() {
        Trip trip = trip();
        Story story = story(trip, Story.Status.COMPLETED);
        TripDetailPlace place = TripDetailPlace.fromAnalysis(trip.getId(), 1, "장소", BigDecimal.ZERO, BigDecimal.ZERO, LocalDateTime.of(2026, 10, 7, 12, 0), LocalDateTime.of(2026, 10, 7, 13, 0), null);
                em.persist(place);
        StoryBlock first = block(story, trip, place, 2);
        StoryBlock second = block(story, trip, null, 1);
        StoryBlock third = block(story, trip, place, 1);
        StoryBlock deleted = block(story, trip, place, 0);
        deleted.softDelete(LocalDateTime.now());
        em.find(TripAttachment.class, first.getTripAttachmentId()).softDelete(LocalDateTime.now());
        TripAttachment thirdAttachment = em.find(TripAttachment.class, third.getTripAttachmentId());
        em.find(StoredFile.class, thirdAttachment.getFileId()).softDelete(LocalDateTime.now());
        ReflectionTestUtils.setField(place, "deletedAt", LocalDateTime.now());
        em.flush();
        em.clear();
        Statistics statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        List<StoryBlockRepository.BlockDetail> rows = blocks.findDetailsByStoryId(story.getId());
        assertThat(rows).extracting(row -> row.getBlock().getId()).containsExactly(second.getId(), third.getId(), first.getId());
        assertThat(rows.getFirst().getPlace()).isNull();
        for (StoryBlockRepository.BlockDetail row : rows) {
            assertThat(row.getAttachment().getTripId()).isEqualTo(trip.getId());
            assertThat(row.getAttachment().getPreviewStorageKey()).isEqualTo("미리보기");
            row.getFile().getDeletedAt();
            row.getBlock().getDayLabel();
            if (row.getPlace() != null) {
                assertThat(row.getPlace().getStartedAt()).isEqualTo(LocalDateTime.of(2026, 10, 7, 12, 0));
            }
        }
        assertThat(rows.get(1).getFile().getDeletedAt()).isNotNull();
        assertThat(rows.get(2).getAttachment().getDeletedAt()).isNotNull();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    private Trip trip() {
        User user = new User(java.util.UUID.randomUUID()+"@story.test", "작성자");
        em.persist(user);
        Trip trip = new Trip(user.getUserId(), "여행", LocalDate.now(), LocalDate.now());
        em.persist(trip);
        return trip;
    }

    private Story story(Trip trip, Story.Status status) {
        Story story = new Story(trip.getId(), Story.Mood.CALM, "요약");
        ReflectionTestUtils.setField(story, "processingStatus", status);
        em.persist(story);
        return story;
    }

    private void select(Trip trip, Story story) {
        ReflectionTestUtils.setField(trip, "currentStoryId", story.getId());
        em.flush();
    }

    private StoryBlock block(Story story, Trip trip, TripDetailPlace place, int order) {
        StoredFile file = StoredFile.uploaded(trip.getUserId(), "사진.jpg", "원본", "image/jpeg");
        em.persist(file);
        TripAttachment attachment = TripAttachment.initial(trip.getId(), file.getId(), "분석", "미리보기");
        if (place != null) {
            attachment.classify(place.getId(), RegionOrigin.EXIF, place.getStartedAt(), BigDecimal.ZERO, BigDecimal.ZERO, 1);
        }
        em.persist(attachment);
        StoryBlock block = new StoryBlock(story.getId(), attachment.getId(), order, "첫째 날", "문구", "편집 메모");
        em.persist(block);
        return block;
    }
}
