package com.yeodam.yeodambe.story.entity;

import com.yeodam.yeodambe.common.exception.InvalidStoryUpdateException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class StoryEntityTest {
    @Test
    void 기존_스토리의_ID와_상태를_유지하며_분위기와_요약만_수정한다() {
        Story story = new Story(7L, Story.Mood.PLAIN, "기존 요약");
        ReflectionTestUtils.setField(story, "id", 3L);
        story.complete();

        story.updateContent(Story.Mood.CALM, "😀".repeat(20));

        assertThat(story.getId()).isEqualTo(3L);
        assertThat(story.getTripId()).isEqualTo(7L);
        assertThat(story.getProcessingStatus()).isEqualTo(Story.Status.COMPLETED);
        assertThat(story.getMood()).isEqualTo(Story.Mood.CALM);
        assertThat(story.getStorySummary()).isEqualTo("😀".repeat(20));
    }

    @Test
    void 잘못된_요약이나_분위기이면_기존_내용을_변경하지_않는다() {
        Story story = new Story(7L, Story.Mood.PLAIN, "기존 요약");
        for (String summary : new String[]{null, "", " ", "\u200B", "😀".repeat(21)}) {
            assertThatThrownBy(() -> story.updateContent(Story.Mood.CALM, summary))
                    .isInstanceOf(InvalidStoryUpdateException.class);
        }
        assertThatThrownBy(() -> story.updateContent(null, "새 요약"))
                .isInstanceOf(InvalidStoryUpdateException.class);
        assertThat(story.getMood()).isEqualTo(Story.Mood.PLAIN);
        assertThat(story.getStorySummary()).isEqualTo("기존 요약");
    }

    @Test
    void 메모와_순서만_변경하면_사진과_날짜_장소_문구는_유지한다() {
        var date = LocalDate.of(2026, 10, 13);
        var block = new StoryBlock(1L, 2L, 5L, date, 1, "첫째 날", "설명", "기존 메모");

        block.updateMemoAndOrder("😀".repeat(125), 2);

        assertThat(block.getMemo()).isEqualTo("😀".repeat(125));
        assertThat(block.getOrderNumber()).isEqualTo(2);
        assertThat(block.getStoryId()).isEqualTo(1L);
        assertThat(block.getTripAttachmentId()).isEqualTo(2L);
        assertThat(block.getSourceTripPlaceId()).isEqualTo(5L);
        assertThat(block.getSourceDate()).isEqualTo(date);
        assertThat(block.getDayLabel()).isEqualTo("첫째 날");
        assertThat(block.getDetailSummary()).isEqualTo("설명");
        block.updateMemoAndOrder(null, 1);
        assertThat(block.getMemo()).isNull();
    }

    @Test
    void 잘못된_메모나_순서이면_블록의_기존_내용을_유지한다() {
        var block = new StoryBlock(1L, 2L, 5L, LocalDate.of(2026, 10, 13), 1,
                "첫째 날", "설명", "기존 메모");
        assertThatThrownBy(() -> block.updateMemoAndOrder("😀".repeat(126), 2))
                .isInstanceOf(InvalidStoryUpdateException.class);
        for (int order : new int[]{0, -1}) {
            assertThatThrownBy(() -> block.updateMemoAndOrder("새 메모", order))
                    .isInstanceOf(InvalidStoryUpdateException.class);
        }
        assertThat(block.getMemo()).isEqualTo("기존 메모");
        assertThat(block.getOrderNumber()).isEqualTo(1);
    }

    @Test
    void 삭제된_블록_복구는_삭제_표시만_해제한다() {
        var block = new StoryBlock(1L, 2L, 5L, LocalDate.of(2026, 10, 13), 1,
                "첫째 날", "설명", "메모");
        ReflectionTestUtils.setField(block, "id", 9L);
        block.softDelete(LocalDateTime.of(2026, 10, 30, 12, 0));
        block.updateMemoAndOrder("변경 메모", 2);
        assertThat(block.getDeletedAt()).isNotNull();

        block.restore();
        block.restore();

        assertThat(block.getDeletedAt()).isNull();
        assertThat(block.getId()).isEqualTo(9L);
        assertThat(block.getTripAttachmentId()).isEqualTo(2L);
        assertThat(block.getMemo()).isEqualTo("변경 메모");
        assertThat(block.getOrderNumber()).isEqualTo(2);
    }

    @Test
    void 이모지가_포함된_최대_길이의_문구와_메모를_저장한다() {
        String text = "가".repeat(39) + "😀";
        String memo = "나".repeat(124) + "😀";
        Story story = new Story(1L, Story.Mood.PLAIN, text);
        StoryBlock block = new StoryBlock(
                1L, 2L, 5L, LocalDate.of(2026, 10, 13),
                1, text, text, memo
        );

        assertThat(story.getStorySummary()).isEqualTo(text);
        assertThat(block.getDayLabel()).isEqualTo(text);
        assertThat(block.getDetailSummary()).isEqualTo(text);
        assertThat(block.getMemo()).isEqualTo(memo);
    }

    @Test
    void 코드_포인트_제한을_초과한_문구와_메모를_저장하지_않는다() {
        String text = "😀".repeat(41);
        LocalDate date = LocalDate.of(2026, 10, 13);
        assertThatThrownBy(() -> new Story(1L, Story.Mood.PLAIN, text))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoryBlock(1L, 2L, 5L, date, 1, text, "설명", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoryBlock(1L, 2L, 5L, date, 1, "첫날", text, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoryBlock(1L, 2L, 5L, date, 1, "첫날", "설명", "😀".repeat(126)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 저장_문구의_필수값과_길이를_검증한다() {
        assertThat(new Story(1L, Story.Mood.PLAIN, "가".repeat(40)).getStorySummary()).hasSize(40);
        for (String summary : new String[]{null, "", " ", "가".repeat(41)}) {
            assertThatThrownBy(() -> new Story(1L, Story.Mood.PLAIN, summary))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new Story(null, Story.Mood.PLAIN, "요약")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Story(1L, null, "요약")).isInstanceOf(IllegalArgumentException.class);
        for (String memo : new String[]{null, "", "가".repeat(125)}) {
            assertThat(new StoryBlock(1L, 2L, 5L, java.time.LocalDate.of(2026, 10, 13), 0, "가".repeat(40), "가".repeat(40), memo).getMemo()).isEqualTo(memo);
        }
        for (String text : new String[]{null, "", " ", "가".repeat(41)}) {
            assertThatThrownBy(() -> new StoryBlock(1L, 2L, 5L, java.time.LocalDate.of(2026, 10, 13), 1, text, "문구", null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StoryBlock(1L, 2L, 5L, java.time.LocalDate.of(2026, 10, 13), 1, "날짜", text, null)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new StoryBlock(1L, 2L, 5L, java.time.LocalDate.of(2026, 10, 13), 1, "날짜", "문구", "가".repeat(126)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 양방향_관계를_동기화하고_중복과_이력_이동을_막는다() {
        Story story = new Story(1L, Story.Mood.CALM, "요약");
        ReflectionTestUtils.setField(story, "id", 3L);
        StoryBlock block = new StoryBlock(3L, 4L, 5L, java.time.LocalDate.of(2026, 10, 13), 1, "첫째 날", "문구", null);
        StorySelectedPlace place = new StorySelectedPlace(3L, 5L);
        story.addBlock(block);
        story.addBlock(block);
        story.addSelectedPlace(place);
        story.addSelectedPlace(place);
        assertThat(story.getBlocks()).containsExactly(block);
        assertThat(story.getSelectedPlaces()).containsExactly(place);
        assertThat(block.getStory()).isSameAs(story);
        assertThat(block.getStoryId()).isEqualTo(3L);
        assertThat(place.getStory()).isSameAs(story);
        assertThat(place.getStoryId()).isEqualTo(3L);
        assertThatThrownBy(() -> story.getBlocks().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> story.getSelectedPlaces().clear()).isInstanceOf(UnsupportedOperationException.class);
        Story other = new Story(1L, Story.Mood.CALM, "이력");
        ReflectionTestUtils.setField(other, "id", 6L);
        assertThatThrownBy(() -> other.addBlock(block)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> other.addSelectedPlace(place)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Story(1L, Story.Mood.CALM, "미저장").addBlock(block))
                .isInstanceOf(IllegalArgumentException.class);
        block.softDelete(LocalDateTime.now());
        place.softDelete(LocalDateTime.now());
        assertThat(block.getStory()).isSameAs(story);
        assertThat(place.getStoryId()).isEqualTo(3L);
        assertThat(story.getBlocks()).containsExactly(block);
    }
}
