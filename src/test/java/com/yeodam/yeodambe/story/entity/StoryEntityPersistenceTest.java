package com.yeodam.yeodambe.story.entity;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class StoryEntityPersistenceTest {
    @Autowired
    EntityManager em;

    @Test
    void 재조회시_LAZY_관계와_소프트삭제_참조를_보존한다() {
        User user = new User("story-persist@test.com", "작성자");
        em.persist(user);
        Trip trip = new Trip(user.getUserId(), "여행", LocalDate.now(), LocalDate.now());
        em.persist(trip);
        TripDetailPlace place = TripDetailPlace.fromAnalysis(trip.getId(), 1, "장소", BigDecimal.ZERO, BigDecimal.ZERO, LocalDateTime.now(), LocalDateTime.now(), null);
        em.persist(place);
        StoredFile file = StoredFile.uploaded(user.getUserId(), "사진.jpg", "원본", "image/jpeg");
        em.persist(file);
        TripAttachment attachment = TripAttachment.initial(trip.getId(), file.getId(), "분석", "미리보기");
        em.persist(attachment);
        Story story = new Story(trip.getId(), Story.Mood.PLAIN, "요약");
        em.persist(story);
        StoryBlock block = new StoryBlock(story.getId(), attachment.getId(), place.getId(),
                place.getStartedAt().toLocalDate(), 1, "첫째 날", "장소", null);
        StorySelectedPlace selected = new StorySelectedPlace(story.getId(), place.getId());
        story.addBlock(block);
        story.addSelectedPlace(selected);
        em.persist(block);
        em.persist(selected);
        em.flush();
        em.clear();
        Story found = em.find(Story.class, story.getId());
        assertThat(Hibernate.isInitialized(found.getTrip())).isFalse();
        assertThat(Hibernate.isInitialized(ReflectionTestUtils.getField(found, "blocks"))).isFalse();
        StoryBlock foundBlock = found.getBlocks().getFirst();
        assertThat(foundBlock.getStory()).isSameAs(found);
        assertThat(foundBlock.getStoryId()).isEqualTo(found.getId());
        assertThat(foundBlock.getSourceTripPlaceId()).isEqualTo(place.getId());
        assertThat(foundBlock.getSourceDate()).isEqualTo(place.getStartedAt().toLocalDate());
        assertThat(Hibernate.isInitialized(foundBlock.getAttachment())).isFalse();
        StorySelectedPlace foundPlace = found.getSelectedPlaces().getFirst();
        assertThat(foundPlace.getStory()).isSameAs(found);
        assertThat(Hibernate.isInitialized(foundPlace.getTripPlace())).isFalse();
        foundBlock.softDelete(LocalDateTime.now());
        foundPlace.softDelete(LocalDateTime.now());
        em.flush();
        em.clear();
        assertThat(em.find(StoryBlock.class, block.getId()).getStoryId()).isEqualTo(story.getId());
        assertThat(em.find(StorySelectedPlace.class, selected.getId()).getTripPlaceId()).isEqualTo(place.getId());
        assertThat(em.find(TripAttachment.class, attachment.getId()).getDeletedAt()).isNull();
        assertThat(em.find(StoredFile.class, file.getId()).getDeletedAt()).isNull();
        assertThat(em.find(Trip.class, trip.getId()).getDeletedAt()).isNull();
        assertThat(em.find(Story.class, story.getId()).getDeletedAt()).isNull();

        Story restoredStory = em.find(Story.class, story.getId());
        StoryBlock restoredBlock = em.find(StoryBlock.class, block.getId());
        restoredStory.updateContent(Story.Mood.CALM, "수정 요약");
        restoredBlock.updateMemoAndOrder("수정 메모", 2);
        restoredBlock.restore();
        em.flush();
        em.clear();

        assertThat(em.find(Story.class, story.getId()).getStorySummary()).isEqualTo("수정 요약");
        assertThat(em.find(Story.class, story.getId()).getMood()).isEqualTo(Story.Mood.CALM);
        StoryBlock reloaded = em.find(StoryBlock.class, block.getId());
        assertThat(reloaded.getDeletedAt()).isNull();
        assertThat(reloaded.getMemo()).isEqualTo("수정 메모");
        assertThat(reloaded.getOrderNumber()).isEqualTo(2);
        assertThat(reloaded.getTripAttachmentId()).isEqualTo(attachment.getId());
        assertThat(reloaded.getSourceTripPlaceId()).isEqualTo(place.getId());
        assertThat(reloaded.getSourceDate()).isEqualTo(place.getStartedAt().toLocalDate());

        TripDetailPlace replacementPlace = TripDetailPlace.fromAnalysis(trip.getId(), 2, "새 장소",
                BigDecimal.ZERO, BigDecimal.ZERO, LocalDateTime.of(2026, 10, 14, 12, 0),
                LocalDateTime.of(2026, 10, 14, 13, 0), null);
        em.persist(replacementPlace);
        StoredFile replacementFile = StoredFile.uploaded(user.getUserId(), "새사진.jpg", "새원본", "image/jpeg");
        em.persist(replacementFile);
        TripAttachment replacement = TripAttachment.initial(trip.getId(), replacementFile.getId(), "새분석", "새미리보기");
        em.persist(replacement);
        reloaded.replaceAttachment(replacement.getId(), replacementPlace.getId(),
                replacementPlace.getStartedAt().toLocalDate());
        reloaded.updateContent("새 날짜 문구", "새 설명", null, 1);
        em.flush();
        em.clear();

        StoryBlock changed = em.find(StoryBlock.class, block.getId());
        assertThat(changed.getTripAttachmentId()).isEqualTo(replacement.getId());
        assertThat(changed.getAttachment().getId()).isEqualTo(replacement.getId());
        assertThat(changed.getSourceTripPlaceId()).isEqualTo(replacementPlace.getId());
        assertThat(changed.getSourceDate()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(changed.getDayLabel()).isEqualTo("새 날짜 문구");
        assertThat(changed.getDetailSummary()).isEqualTo("새 설명");
        assertThat(em.find(TripAttachment.class, attachment.getId()).getDeletedAt()).isNull();
    }
}
