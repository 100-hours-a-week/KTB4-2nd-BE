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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
        assertThat(rows.getFirst().getAttachment().getTripPlaceId()).isNull();
        assertThat(rows.getFirst().getPlace().getId()).isEqualTo(second.getSourceTripPlaceId());
        for (StoryBlockRepository.BlockDetail row : rows) {
            assertThat(row.getAttachment().getTripId()).isEqualTo(trip.getId());
            assertThat(row.getAttachment().getPreviewStorageKey()).isEqualTo("미리보기");
            row.getFile().getDeletedAt();
            row.getBlock().getDayLabel();
            if (row.getPlace() != null) {
                assertThat(row.getPlace().getId()).isEqualTo(row.getBlock().getSourceTripPlaceId());
                assertThat(row.getPlace().getStartedAt().toLocalDate()).isEqualTo(row.getBlock().getSourceDate());
            }
        }
        assertThat(rows.get(1).getFile().getDeletedAt()).isNotNull();
        assertThat(rows.get(2).getAttachment().getDeletedAt()).isNotNull();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void 수정_대상은_본인의_현재_완료_스토리만_조회한다() {
        Trip trip = trip();
        ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
        Story history = story(trip, Story.Status.COMPLETED);
        Story current = story(trip, Story.Status.COMPLETED);
        select(trip, current);
        assertThat(stories.findTripIdByStoryId(current.getId())).contains(trip.getId());
        assertThat(stories.findTripIdByStoryId(-1L)).isEmpty();
        em.flush();
        em.clear();
        assertThat(stories.findOwnedCurrentCompletedById(current.getId(), trip.getUserId()))
                .map(Story::getId).contains(current.getId());
        Trip managedTrip = org.hibernate.Hibernate.unproxy(
                em.find(Trip.class, trip.getId()), Trip.class);
        Story managedCurrent = em.find(Story.class, current.getId());
        assertThat(stories.findOwnedCurrentCompletedById(current.getId(), -1L)).isEmpty();
        assertThat(stories.findOwnedCurrentCompletedById(-1L, trip.getUserId())).isEmpty();
        assertThat(stories.findOwnedCurrentCompletedById(history.getId(), trip.getUserId())).isEmpty();

        for (Story.Status status : List.of(Story.Status.PROCESSING, Story.Status.FAILED, Story.Status.CANCELED)) {
            ReflectionTestUtils.setField(managedCurrent, "processingStatus", status);
            em.flush();
            assertThat(stories.findOwnedCurrentCompletedById(current.getId(), trip.getUserId())).isEmpty();
        }
        ReflectionTestUtils.setField(managedCurrent, "processingStatus", Story.Status.COMPLETED);
        for (ProcessingStatus status : List.of(ProcessingStatus.PROCESSING, ProcessingStatus.FAILED, ProcessingStatus.CANCELED)) {
            ReflectionTestUtils.setField(managedTrip, "processingStatus", status);
            em.flush();
            assertThat(stories.findOwnedCurrentCompletedById(current.getId(), trip.getUserId())).isEmpty();
        }
        ReflectionTestUtils.setField(managedTrip, "processingStatus", ProcessingStatus.COMPLETED);
        managedCurrent.softDelete(LocalDateTime.now());
        em.flush();
        assertThat(stories.findOwnedCurrentCompletedById(current.getId(), trip.getUserId())).isEmpty();
        select(managedTrip, history);
        managedTrip.softDelete(LocalDateTime.now());
        em.flush();
        assertThat(stories.findOwnedCurrentCompletedById(history.getId(), trip.getUserId())).isEmpty();
    }

    @Test
    void 수정용_블록조회는_삭제행을_포함하고_다른_스토리를_섞지_않는다() {
        Trip trip = trip();
        Story story = story(trip, Story.Status.COMPLETED);
        StoryBlock active = block(story, trip, null, 1);
        StoryBlock deleted = block(story, trip, null, 2);
        deleted.softDelete(LocalDateTime.now());
        Story other = story(trip, Story.Status.COMPLETED);
        block(other, trip, null, 1);
        em.flush();
        em.clear();

        assertThat(blocks.findAllByStoryId(story.getId())).extracting(StoryBlock::getId)
                .containsExactlyInAnyOrder(active.getId(), deleted.getId());
        assertThat(blocks.findDetailsByStoryId(story.getId()))
                .extracting(row -> row.getBlock().getId()).containsExactly(active.getId());
        assertThat(blocks.findAllByStoryId(-1L)).isEmpty();
    }

    @Test
    void 본인의_완료_여행에_연결된_현재_완료_스토리만_집계한다() {
        Trip owned = trip();
        ReflectionTestUtils.setField(owned, "processingStatus", ProcessingStatus.COMPLETED);
        story(owned, Story.Status.COMPLETED);
        Story current = story(owned, Story.Status.COMPLETED);
        select(owned, current);

        Trip other = trip();
        ReflectionTestUtils.setField(other, "processingStatus", ProcessingStatus.COMPLETED);
        select(other, story(other, Story.Status.COMPLETED));

        assertThat(stories.countCurrentCompletedByUserId(owned.getUserId())).isEqualTo(1);
        assertThat(stories.countCurrentCompletedByUserId(-1L)).isZero();

        for (Story.Status status : List.of(Story.Status.PROCESSING, Story.Status.FAILED, Story.Status.CANCELED)) {
            ReflectionTestUtils.setField(current, "processingStatus", status);
            em.flush();
            assertThat(stories.countCurrentCompletedByUserId(owned.getUserId())).isZero();
        }
        ReflectionTestUtils.setField(current, "processingStatus", Story.Status.COMPLETED);
        for (ProcessingStatus status : List.of(ProcessingStatus.PROCESSING, ProcessingStatus.FAILED)) {
            ReflectionTestUtils.setField(owned, "processingStatus", status);
            em.flush();
            assertThat(stories.countCurrentCompletedByUserId(owned.getUserId())).isZero();
        }
        ReflectionTestUtils.setField(owned, "processingStatus", ProcessingStatus.COMPLETED);
        current.softDelete(LocalDateTime.now());
        em.flush();
        assertThat(stories.countCurrentCompletedByUserId(owned.getUserId())).isZero();

        Story replacement = story(owned, Story.Status.COMPLETED);
        select(owned, replacement);
        assertThat(stories.countCurrentCompletedByUserId(owned.getUserId())).isEqualTo(1);
        owned.softDelete(LocalDateTime.now());
        em.flush();
        assertThat(stories.countCurrentCompletedByUserId(owned.getUserId())).isZero();
    }

    @Test
    void 다른_여행의_스토리를_잘못_연결해도_집계하지_않는다() {
        Trip owned = trip();
        ReflectionTestUtils.setField(owned, "processingStatus", ProcessingStatus.COMPLETED);
        Trip other = trip();
        select(owned, story(other, Story.Status.COMPLETED));

        assertThat(stories.countCurrentCompletedByUserId(owned.getUserId())).isZero();
    }

    @ParameterizedTest
    @CsvSource({
            "normal,true", "other-user,false", "other-story,false", "missing-block,false",
            "history,false", "processing,false", "failed,false", "canceled,false",
            "deleted-story,false", "deleted-trip,false", "deleted-block,false",
            "deleted-attachment,false", "deleted-file,false", "unclassified,false",
            "deleted-place,false", "attachment-other-trip,false", "place-other-trip,false"
    })
    void 유사조회_기준블록은_소유권과_현재스토리와_정상_자료를_검증한다(
            String scenario, boolean expected
    ) {
        Trip trip = trip();
        Story story = story(trip, Story.Status.COMPLETED);
        select(trip, story);
        TripDetailPlace place = TripDetailPlace.fromAnalysis(trip.getId(), 1, "장소", BigDecimal.ZERO,
                BigDecimal.ZERO, LocalDateTime.of(2026, 10, 30, 12, 0),
                LocalDateTime.of(2026, 10, 30, 13, 0), null);
        em.persist(place);
        StoryBlock block = block(story, trip, place, 1);
        TripAttachment attachment = em.find(TripAttachment.class, block.getTripAttachmentId());
        StoredFile file = em.find(StoredFile.class, attachment.getFileId());
        Long userId = trip.getUserId();
        Long storyId = story.getId();
        Long blockId = block.getId();
        LocalDateTime deletedAt = LocalDateTime.now();

        switch (scenario) {
            case "other-user" -> userId = -1L;
            case "other-story" -> storyId = -1L;
            case "missing-block" -> blockId = -1L;
            case "history" -> select(trip, story(trip, Story.Status.COMPLETED));
            case "processing" -> ReflectionTestUtils.setField(story, "processingStatus", Story.Status.PROCESSING);
            case "failed" -> ReflectionTestUtils.setField(story, "processingStatus", Story.Status.FAILED);
            case "canceled" -> ReflectionTestUtils.setField(story, "processingStatus", Story.Status.CANCELED);
            case "deleted-story" -> story.softDelete(deletedAt);
            case "deleted-trip" -> trip.softDelete(deletedAt);
            case "deleted-block" -> block.softDelete(deletedAt);
            case "deleted-attachment" -> attachment.softDelete(deletedAt);
            case "deleted-file" -> file.softDelete(deletedAt);
            case "unclassified" -> ReflectionTestUtils.setField(attachment, "classificationStatus", ClassificationStatus.UNCLASSIFIED);
            case "deleted-place" -> ReflectionTestUtils.setField(place, "deletedAt", deletedAt);
            case "attachment-other-trip" -> ReflectionTestUtils.setField(attachment, "tripId", trip().getId());
            case "place-other-trip" -> ReflectionTestUtils.setField(place, "tripId", trip().getId());
        }
        em.flush();
        em.clear();

        var actual = blocks.findOwnedCurrentCompletedBlock(storyId, blockId, userId);

        assertThat(actual.isPresent()).isEqualTo(expected);
        if (expected) {
            assertThat(org.hibernate.Hibernate.isInitialized(actual.orElseThrow().getAttachment())).isTrue();
            em.clear();
            assertThat(actual.orElseThrow().getAttachment().getTripPlaceId()).isEqualTo(place.getId());
        }
    }

    @Test
    void 유사조회는_생성당시_장소와_달라도_사진의_현재_폴더를_사용한다() {
        Trip trip = trip();
        Story story = story(trip, Story.Status.COMPLETED);
        select(trip, story);
        TripDetailPlace original = TripDetailPlace.fromAnalysis(trip.getId(), 1, "이전 장소", BigDecimal.ZERO,
                BigDecimal.ZERO, LocalDateTime.of(2026, 10, 30, 12, 0),
                LocalDateTime.of(2026, 10, 30, 13, 0), null);
        em.persist(original);
        StoryBlock block = block(story, trip, original, 1);
        TripDetailPlace current = TripDetailPlace.fromAnalysis(trip.getId(), 2, "현재 장소", BigDecimal.ZERO,
                BigDecimal.ZERO, original.getStartedAt(), original.getStartedAt(), null);
        em.persist(current);
        TripAttachment attachment = em.find(TripAttachment.class, block.getTripAttachmentId());
        attachment.classify(current.getId(), RegionOrigin.EXIF, current.getStartedAt(), BigDecimal.ZERO,
                BigDecimal.ZERO, 1);
        em.flush();
        em.clear();

        StoryBlock actual = blocks.findOwnedCurrentCompletedBlock(story.getId(), block.getId(), trip.getUserId())
                .orElseThrow();

        assertThat(actual.getSourceTripPlaceId()).isEqualTo(original.getId());
        assertThat(actual.getAttachment().getTripPlaceId()).isEqualTo(current.getId());
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
        TripDetailPlace sourcePlace = place;
        if (sourcePlace == null) {
            sourcePlace = TripDetailPlace.fromAnalysis(trip.getId(), 1, "생성 당시 장소", BigDecimal.ZERO,
                    BigDecimal.ZERO, java.time.LocalDateTime.of(2026, 10, 13, 12, 0),
                    java.time.LocalDateTime.of(2026, 10, 13, 13, 0), null);
            em.persist(sourcePlace);
        }
        StoryBlock block = new StoryBlock(story.getId(), attachment.getId(), sourcePlace.getId(),
                sourcePlace.getStartedAt().toLocalDate(), order, "첫째 날", "문구", "편집 메모");
        em.persist(block);
        return block;
    }
}
