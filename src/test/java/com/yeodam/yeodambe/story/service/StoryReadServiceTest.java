package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.exception.*;
import com.yeodam.yeodambe.story.repository.*;
import com.yeodam.yeodambe.story.repository.StoryBlockRepository.BlockDetail;
import com.yeodam.yeodambe.story.service.response.StoryDetailResponse;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.service.TripAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static com.yeodam.yeodambe.story.service.StoryValidatorTest.row;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StoryReadServiceTest {
    @Mock
    TripAccessService access;

    @Mock
    StoryRepository stories;

    @Mock
    StoryBlockRepository blocks;

    @Mock
    TripAttachmentStorageClient storage;
    StoryReadService service;

    @BeforeEach
    void setup() {
        service = new StoryReadService(access, stories, blocks, new StoryValidator(), storage);
    }

    private void found(List<BlockDetail> rows) {
        Story story = new Story(7L, Story.Mood.EMOTIONAL, "여행 요약");
        ReflectionTestUtils.setField(story, "id", 1L);
        when(stories.findCurrentCompletedByTripId(7L)).thenReturn(Optional.of(story));
        when(blocks.findDetailsByStoryId(1L)).thenReturn(rows);
    }

    @Test
    void 저장순서와_인접_날짜라벨_그룹_및_응답문구를_보존한다() {
        var first = row(1, 7, "첫째 날", null);
        var second = row(2, 7, "첫째 날", "사용자 편집");
        found(List.of(first, second, row(3, 8, "둘째 날", ""), row(4, 7, "첫째 날", null), row(5, 7, "다른 라벨", null)));
        when(storage.createReadUrl(anyString())).thenAnswer(call -> "https://image.test/"+call.getArgument(0));
        StoryDetailResponse response = service.findStory(42L, 7L);
        assertThat(response.storyId()).isEqualTo(1L);
        assertThat(response.tripId()).isEqualTo(7L);
        assertThat(response.userByMe()).isTrue();
        assertThat(response.mood()).isEqualTo(Story.Mood.EMOTIONAL);
        assertThat(response.storySummary()).isEqualTo("여행 요약");
        assertThat(response.days()).extracting(day -> day.blocks().size()).containsExactly(2, 1, 1, 1);
        assertThat(response.days()).extracting(StoryDetailResponse.Day::date).containsExactly(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 7));
        var block = response.days().getFirst().blocks().getFirst();
        assertThat(block.storyBlockId()).isEqualTo(1L);
        assertThat(block.orderNumber()).isEqualTo(1);
        assertThat(block.tripPlaceId()).isEqualTo(7L);
        assertThat(block.tripAttachmentId()).isEqualTo(1L);
        assertThat(block.thumbnailUrl()).isEqualTo("https://image.test/preview/1");
        assertThat(block.detailSummary()).isEqualTo("문구1");
        assertThat(block.memo()).isEmpty();
        assertThat(response.days().getFirst().blocks().get(1).memo()).isEqualTo("사용자 편집");
        assertThat(response.days()).extracting(StoryDetailResponse.Day::dayLabel).containsExactly("첫째 날", "둘째 날", "첫째 날", "다른 라벨");
        assertThat(first.block().getMemo()).isNull();
        verify(access).requireReadableTrip(7L, 42L);
        verify(stories, never()).save(any());
        verify(blocks, never()).save(any());
    }

    @Test
    void 삭제사진은_표시검증과_URL발급에서_제외한다() {
        var deleted = row(1, 7, "날", null);
        deleted.attachment().softDelete(LocalDateTime.now());
        ReflectionTestUtils.setField(deleted.attachment(), "previewStorageKey", null);
        var absentPlace = new StoryValidatorTest.Row(deleted.block(), deleted.attachment(), deleted.file(), null);
        var deletedFile = row(2, 7, "날", null);
        deletedFile.file().softDelete(LocalDateTime.now());
        found(List.of(absentPlace, deletedFile));
        StoryDetailResponse response = service.findStory(42L, 7L);
        assertThat(response.days()).isEmpty();
        assertThat(response.storySummary()).isEqualTo("여행 요약");
        assertThat(response.mood()).isEqualTo(Story.Mood.EMOTIONAL);
        verifyNoInteractions(storage);
    }

    @Test
    void 삭제사진_제외전에_개수와_참조를_검증한다() {
        var deleted = row(1, 7, "날", null);
        deleted.file().softDelete(LocalDateTime.now());
        found(Collections.nCopies(11, deleted));
        assertThatThrownBy(() -> service.findStory(42L, 7L)).isInstanceOf(StoryDataIntegrityException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void 모든_표시검증이_완료된_후_URL을_발급한다() {
        var invalid = row(2, 7, "날", null);
        ReflectionTestUtils.setField(invalid.attachment(), "previewStorageKey", " ");
        found(List.of(row(1, 7, "날", null), invalid));
        assertThatThrownBy(() -> service.findStory(42L, 7L)).isInstanceOf(StoryDataIntegrityException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void 권한이_없으면_스토리와_URL을_조회하지_않는다() {
        doThrow(new TripNotFoundException()).when(access).requireReadableTrip(7L, 42L);
        assertThatThrownBy(() -> service.findStory(42L, 7L)).isInstanceOf(StoryNotFoundException.class);
        verifyNoInteractions(stories, blocks, storage);
    }

    @Test
    void 현재_완료_스토리가_없으면_404다() {
        when(stories.findCurrentCompletedByTripId(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findStory(42L, 7L)).isInstanceOf(StoryNotFoundException.class);
        verifyNoInteractions(blocks, storage);
    }

    @Test
    void 스토리지와_DB_오류를_404로_변환하지_않는다() {
        found(List.of(row(1, 7, "날", null)));
        when(storage.createReadUrl("preview/1")).thenThrow(new IllegalStateException("발급 실패"));
        assertThatThrownBy(() -> service.findStory(42L, 7L)).isInstanceOf(IllegalStateException.class);
        doThrow(new DataAccessResourceFailureException("DB 실패")).when(access).requireReadableTrip(7L, 42L);
        assertThatThrownBy(() -> service.findStory(42L, 7L)).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
