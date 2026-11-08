package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.story.entity.StorySelectedPlace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoryContentDocumentFactoryTest {
    private final StoryContentDocumentFactory factory = new StoryContentDocumentFactory();

    @Test
    void 식별자와_순서와_내용과_삭제된_블록_ID를_보존한다() {
        StoryBlock first = block(30L, 101L, 201L, 1);
        StoryBlock second = block(10L, 102L, 202L, 2);
        StoryBlock deleted = block(90L, 103L, 201L, 3);
        deleted.softDelete(LocalDateTime.of(2026, 11, 4, 11, 0));

        StoryContentDocument document = factory.create(story(), List.of(
                new StorySelectedPlace(7L, 202L),
                new StorySelectedPlace(7L, 201L)
        ), List.of(first, second, deleted), ZoneId.of("Asia/Seoul"));

        assertThat(document.getId()).isEqualTo(7L);
        assertThat(document.getMood()).isEqualTo(Story.Mood.PLAIN);
        assertThat(document.getStorySummary()).isEqualTo("여행 요약");
        assertThat(document.getSelectedPlaceIds()).containsExactly(202L, 201L);
        assertThat(document.getBlocks()).extracting(StoryContentBlock::id)
                .containsExactly(30L, 10L, 90L);
        assertThat(document.getNextBlockId()).isEqualTo(91L);
        assertThat(document.getVersion()).isNull();
        StoryContentBlock copied = document.getBlocks().getFirst();
        assertThat(copied.tripAttachmentId()).isEqualTo(101L);
        assertThat(copied.sourceTripPlaceId()).isEqualTo(201L);
        assertThat(copied.sourceDate()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(copied.dayLabel()).isEqualTo("첫날");
        assertThat(copied.detailSummary()).isEqualTo("사진 설명");
        assertThat(copied.memo()).isEqualTo("산책");
        assertThat(copied.deletedAt()).isNull();
        assertThat(document.getBlocks().get(2).deletedAt())
                .isEqualTo(Instant.parse("2026-11-04T02:00:00Z"));
        assertThat(deleted.getDeletedAt()).isEqualTo(LocalDateTime.of(2026, 11, 4, 11, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Seoul", "America/Los_Angeles"})
    void 명시한_원본_시간대로_타임스탬프를_해석한다(String sourceZone) {
        ZoneId zone = ZoneId.of(sourceZone);
        Instant created = Instant.parse("2026-11-04T01:00:00Z");
        Instant updated = Instant.parse("2026-11-04T02:00:00Z");
        StoryBlock source = block(10L, 101L, 201L, 1);
        ReflectionTestUtils.setField(source, "createdAt", LocalDateTime.ofInstant(created, zone));
        ReflectionTestUtils.setField(source, "updatedAt", LocalDateTime.ofInstant(updated, zone));

        StoryContentDocument document = factory.create(story(), List.of(), List.of(source), zone);

        assertThat(document.getBlocks().getFirst().createdAt()).isEqualTo(created);
        assertThat(document.getBlocks().getFirst().updatedAt()).isEqualTo(updated);
        assertThat(document.getBlocks().getFirst().sourceDate())
                .isEqualTo(LocalDate.of(2026, 11, 3));
    }

    @Test
    void 빈_문서의_블록_ID는_1부터_시작한다() {
        StoryContentDocument document = factory.create(story(), List.of(), List.of(), ZoneId.of("UTC"));

        assertThat(document.getBlocks()).isEmpty();
        assertThat(document.getNextBlockId()).isEqualTo(1L);
    }

    @Test
    void 기존_SQL블록의_ID와_순서를_보존하며_프리뷰키를_저장한다() {
        Story story = new Story(7L, Story.Mood.PLAIN, "요약");
        ReflectionTestUtils.setField(story, "id", 10L);
        StoryBlock source = block(91L, 91L, 7L, 1);

        StoryContentDocument result = new StoryContentDocumentFactory().create(
                story, List.of(), List.of(source), ZoneOffset.UTC, Map.of(91L, "preview/new")
        );

        assertThat(result.getBlocks().getFirst().id()).isEqualTo(91L);
        assertThat(result.getBlocks().getFirst().previewStorageKey()).isEqualTo("preview/new");
        assertThat(result.getNextBlockId()).isEqualTo(92L);
    }

    private Story story() {
        Story story = new Story(1L, Story.Mood.PLAIN, "여행 요약");
        ReflectionTestUtils.setField(story, "id", 7L);
        return story;
    }

    private StoryBlock block(Long id, Long photoId, Long placeId, int order) {
        StoryBlock block = new StoryBlock(7L, photoId, placeId,
                LocalDate.of(2026, 11, 3), order, "첫날", "사진 설명", "산책");
        ReflectionTestUtils.setField(block, "id", id);
        ReflectionTestUtils.setField(block, "createdAt", LocalDateTime.of(2026, 11, 4, 10, 0));
        ReflectionTestUtils.setField(block, "updatedAt", LocalDateTime.of(2026, 11, 4, 10, 30));
        return block;
    }
}
