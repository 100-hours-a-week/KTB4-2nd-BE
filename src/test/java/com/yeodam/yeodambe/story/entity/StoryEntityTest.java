package com.yeodam.yeodambe.story.entity;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class StoryEntityTest {
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
