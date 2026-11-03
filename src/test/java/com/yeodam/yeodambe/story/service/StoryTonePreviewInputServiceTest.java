package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.InvalidStoryTonePreviewException;
import com.yeodam.yeodambe.common.exception.StoryNotFoundException;
import com.yeodam.yeodambe.common.exception.StoryTonePreviewTakenAtMissingException;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.repository.StoryBlockRepository;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.service.request.StoryTonePreviewRequest;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoryTonePreviewInputServiceTest {
    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private final StoryRepository stories = mock(StoryRepository.class);
    private final StoryBlockRepository blocks = mock(StoryBlockRepository.class);
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final StoryTonePreviewInputService service = new StoryTonePreviewInputService(
            stories, blocks, attachments,
            new StoryTonePreviewRequestValidator(factory.getValidator()), new StoryValidator());

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void 편집순서대로_DB정보와_FE문구를_합치고_빈메모를_null로_보낸다() {
        arrangeStory();
        var firstPhoto = photo(101L);
        var secondPhoto = photo(102L);
        when(attachments.findAllActiveWithTripAndFileByIds(List.of(102L, 101L)))
                .thenReturn(List.of(firstPhoto, secondPhoto));
        var request = new StoryTonePreviewRequest(Story.Mood.HUMOROUS, List.of(
                new StoryTonePreviewRequest.Block(null, 102L, 2, null, null, ""),
                new StoryTonePreviewRequest.Block(null, 101L, 1, "현재 라벨", "현재 캡션", "현재 메모")));

        var prepared = service.prepare(2L, 9L, request);

        assertThat(prepared.tripId()).isEqualTo(7L);
        assertThat(prepared.request().tripName()).isEqualTo("제주 여행");
        assertThat(prepared.request().period().startDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(prepared.request().period().endDate()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(prepared.request().mood()).isEqualTo(Story.Mood.HUMOROUS);
        var first = prepared.request().blocks().getFirst();
        assertThat(first.tripAttachmentId()).isEqualTo(101L);
        assertThat(first.orderNumber()).isEqualTo(1);
        assertThat(first.placeName()).isEqualTo("장소101");
        assertThat(first.analyzeStorageKey()).isEqualTo("analyze/101.jpg");
        assertThat(first.takenAt()).isEqualTo(
                LocalDateTime.of(2026, 10, 1, 12, 0).atOffset(ZoneOffset.ofHours(9)));
        assertThat(first.dayLabel()).isEqualTo("현재 라벨");
        assertThat(first.detailSummary()).isEqualTo("현재 캡션");
        assertThat(first.memo()).isEqualTo("현재 메모");
        assertThat(prepared.request().blocks().get(1).tripAttachmentId()).isEqualTo(102L);
        assertThat(prepared.request().blocks().get(1).memo()).isNull();
        verify(stories, never()).save(any());
        verify(blocks, never()).save(any());
    }

    @Test
    void 잘못된입력이면_DB조회전에_거절한다() {
        assertThatThrownBy(() -> service.prepare(2L, 9L, null))
                .isInstanceOf(InvalidStoryTonePreviewException.class);
        verifyNoInteractions(stories, blocks, attachments);
    }

    @Test
    void 접근가능한스토리가_없으면_사진조회전에_거절한다() {
        when(stories.findOwnedCurrentCompletedForPreviewById(9L, 2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.prepare(2L, 9L, request()))
                .isInstanceOf(StoryNotFoundException.class);
        verifyNoInteractions(blocks, attachments);
    }

    @Test
    void 촬영시각이_없으면_요청조립을_완료하지않는다() {
        arrangeStory();
        var photo = photo(101L);
        when(photo.getTakenAt()).thenReturn(null);
        when(attachments.findAllActiveWithTripAndFileByIds(List.of(101L))).thenReturn(List.of(photo));
        assertThatThrownBy(() -> service.prepare(2L, 9L, request()))
                .isInstanceOf(StoryTonePreviewTakenAtMissingException.class);
    }

    private void arrangeStory() {
        var story = mock(Story.class);
        when(story.getTripId()).thenReturn(7L);
        when(story.getTrip()).thenReturn(new Trip(2L, "제주 여행",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3)));
        when(stories.findOwnedCurrentCompletedForPreviewById(9L, 2L)).thenReturn(Optional.of(story));
        when(blocks.findAllByStoryId(9L)).thenReturn(List.of());
    }

    private TripAttachment photo(Long id) {
        var photo = mock(TripAttachment.class);
        var place = mock(TripDetailPlace.class);
        when(photo.getId()).thenReturn(id);
        when(photo.getTripId()).thenReturn(7L);
        when(photo.getClassificationStatus()).thenReturn(ClassificationStatus.ACTIVE);
        when(photo.getTakenAt()).thenReturn(LocalDateTime.of(2026, 10, 1, 12, 0));
        when(photo.getAnalyzeStorageKey()).thenReturn("analyze/" + id + ".jpg");
        when(photo.getTripPlace()).thenReturn(place);
        when(place.getTripId()).thenReturn(7L);
        when(place.getPlaceName()).thenReturn("장소" + id);
        return photo;
    }

    private StoryTonePreviewRequest request() {
        return new StoryTonePreviewRequest(Story.Mood.PLAIN,
                List.of(new StoryTonePreviewRequest.Block(null, 101L, 1, null, null, null)));
    }
}
