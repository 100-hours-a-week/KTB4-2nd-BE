package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.common.exception.*;
import com.yeodam.yeodambe.story.repository.*;
import com.yeodam.yeodambe.story.repository.StoryBlockRepository.BlockDetail;
import com.yeodam.yeodambe.story.service.response.StoryDetailResponse;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
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
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.story.service.response.StoryContentBlockDetail;
import com.yeodam.yeodambe.story.service.response.StoryReadContext;
import com.yeodam.yeodambe.story.service.response.StoryReadContent;
import com.yeodam.yeodambe.trip.service.TripEditAuthorizationService;
import java.time.ZoneOffset;
import java.util.stream.IntStream;

import static com.yeodam.yeodambe.story.service.StoryValidatorTest.row;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StoryReadServiceTest {
    @Mock
    TripEditAuthorizationService access;

    @Mock
    StoryRepository stories;

    @Mock
    StoryBlockReferenceQuery references;

    @Mock
    TripAttachmentStorageClient storage;
    @Mock
    StoryContentRepository contents;
    StoryReadService service;

    @BeforeEach
    void setup() {
        service = new StoryReadService(access, stories, new StoryValidator(), storage, contents, references);
        lenient().when(stories.findReadContextByTripId(7L)).thenReturn(Optional.of(
                new StoryReadContext(7L, 42L, 1L)));

    }

    private void found(List<BlockDetail> rows) {
        Story story = new Story(7L, Story.Mood.EMOTIONAL, "여행 요약");
        ReflectionTestUtils.setField(story, "id", 1L);

        List<StoryContentBlock> documentBlocks = new ArrayList<>();
        List<StoryContentBlockDetail> details = new ArrayList<>();
        for (BlockDetail row : rows) {
            StoryBlock block = row.getBlock();
            documentBlocks.add(new StoryContentBlock(
                    block.getId(), block.getTripAttachmentId(), block.getSourceTripPlaceId(),
                    block.getSourceDate(), block.getDayLabel(), block.getDetailSummary(), block.getMemo(),
                    null, null, block.getDeletedAt() == null ? null
                            : block.getDeletedAt().toInstant(ZoneOffset.UTC)));
            if (block.getDeletedAt() == null) {
                details.add(StoryValidatorTest.contentDetail(
                        documentBlocks.getLast(), row.getAttachment(), row.getFile(), row.getPlace()));
            }
        }
        when(contents.findActiveContentById(1L)).thenReturn(Optional.of(new StoryReadContent(
                1L, story.getMood(), story.getStorySummary(), documentBlocks)));
        lenient().when(references.findByBlocks(anyList())).thenReturn(details);
    }

    @Test
    void 분위기와_요약은_MySQL이_아닌_MongoDB_본문을_사용한다() {
        found(List.of());
        when(contents.findActiveContentById(1L)).thenReturn(Optional.of(new StoryReadContent(
                1L, Story.Mood.CALM, "문서의 요약", List.of())));

        StoryDetailResponse response = service.findStory(42L, 7L);

        assertThat(response.mood()).isEqualTo(Story.Mood.CALM);
        assertThat(response.storySummary()).isEqualTo("문서의 요약");
        verifyNoInteractions(references, storage);
    }

    @Test
    void 완료된_스토리의_문서가_없으면_정합성_오류다() {
        found(List.of());
        when(contents.findActiveContentById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findStory(42L, 7L))
                .isInstanceOf(StoryDataIntegrityException.class)
                .hasMessage("완료된 스토리의 MongoDB 본문이 없습니다.");
        verifyNoInteractions(references, storage);
    }

    @Test
    void 사진_조회순서와_무관하게_문서_배열순서와_블록ID를_유지한다() {
        StoryValidatorTest.Row first = row(9, 7, "날", null);
        StoryValidatorTest.Row second = row(3, 7, "날", null);
        found(List.of(first, second));
        when(storage.createReadUrl(anyString())).thenAnswer(call -> "url/" + call.getArgument(0));

        List<StoryDetailResponse.Block> blocks = service.findStory(42L, 7L).days().getFirst().blocks();

        assertThat(blocks).extracting(StoryDetailResponse.Block::storyBlockId).containsExactly(9L, 3L);
        assertThat(blocks).extracting(StoryDetailResponse.Block::tripAttachmentId).containsExactly(9L, 3L);
        assertThat(blocks).extracting(StoryDetailResponse.Block::orderNumber).containsExactly(1, 2);
        verify(references).findByBlocks(argThat(items -> items.stream()
                .map(StoryContentBlock::id).toList().equals(List.of(9L, 3L))));
    }

    @Test
    void 삭제된_문서블록은_사진조회에서_제외하고_삭제사진은_순서만_남긴다() {
        StoryValidatorTest.Row removed = row(1, 7, "날", null);
        removed.block().softDelete(LocalDateTime.now());
        StoryValidatorTest.Row deletedPhoto = row(2, 7, "날", null);
        deletedPhoto.attachment().softDelete(LocalDateTime.now());
        found(List.of(removed, deletedPhoto, row(3, 7, "날", null)));
        when(storage.createReadUrl("preview/3")).thenReturn("url/3");

        List<StoryDetailResponse.Block> blocks = service.findStory(42L, 7L).days().getFirst().blocks();

        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst().storyBlockId()).isEqualTo(3L);
        assertThat(blocks.getFirst().orderNumber()).isEqualTo(2);
        verify(references).findByBlocks(argThat(items -> items.stream()
                .map(StoryContentBlock::id).toList().equals(List.of(2L, 3L))));
        verify(storage).createReadUrl("preview/3");
        verifyNoMoreInteractions(storage);
    }

    @Test
    void 참조된_사진이_없으면_URL발급전에_거절한다() {
        StoryValidatorTest.Row source = row(1, 7, "날", null);
        found(List.of(new StoryValidatorTest.Row(source.block(), null, source.file(), source.place())));

        assertThatThrownBy(() -> service.findStory(42L, 7L))
                .isInstanceOf(StoryDataIntegrityException.class)
                .hasMessage("블록 관계: 첨부가 없습니다.");
        verifyNoInteractions(storage);
    }

    @Test
    void 파일이_없는_사진도_URL발급전에_거절한다() {
        StoryValidatorTest.Row source = row(1, 7, "날", null);
        found(List.of(new StoryValidatorTest.Row(source.block(), source.attachment(), null, source.place())));

        assertThatThrownBy(() -> service.findStory(42L, 7L))
                .isInstanceOf(StoryDataIntegrityException.class)
                .hasMessage("블록 관계: 파일이 없습니다.");
        verifyNoInteractions(storage);
    }

    @Test
    void 저장순서와_인접_날짜라벨_그룹_및_응답문구를_보존한다() {
        StoryValidatorTest.Row first = row(1, 7, "첫째 날", null);
        StoryValidatorTest.Row second = row(2, 7, "첫째 날", "사용자 편집");
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
        StoryDetailResponse.Block block = response.days().getFirst().blocks().getFirst();
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
        verify(access).requireReadPermission(7L, 42L, 42L);
        verify(stories, never()).save(any());
    }

    @Test
    void 공유_사용자는_스토리를_조회하되_소유자로_표시하지_않는다() {
        when(stories.findReadContextByTripId(7L)).thenReturn(Optional.of(
                new StoryReadContext(7L, 1L, 1L)));
        found(List.of());

        assertThat(service.findStory(42L, 7L).userByMe()).isFalse();
        verify(access).requireReadPermission(7L, 1L, 42L);
    }

    @Test
    void 삭제사진은_표시검증과_URL발급에서_제외한다() {
        StoryValidatorTest.Row deleted = row(1, 7, "날", null);
        deleted.attachment().softDelete(LocalDateTime.now());
        ReflectionTestUtils.setField(deleted.attachment(), "previewStorageKey", null);
        StoryValidatorTest.Row absentPlace = new StoryValidatorTest.Row(deleted.block(), deleted.attachment(), deleted.file(), null);
        StoryValidatorTest.Row deletedFile = row(2, 7, "날", null);
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
        StoryValidatorTest.Row deleted = row(1, 7, "날", null);
        deleted.file().softDelete(LocalDateTime.now());
        found(Collections.nCopies(31, deleted));
        assertThatThrownBy(() -> service.findStory(42L, 7L)).isInstanceOf(StoryDataIntegrityException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void 생성_계약의_최대_30개_블록을_상세_응답으로_반환한다() {
        List<BlockDetail> rows = IntStream.rangeClosed(1, 30)
                .<BlockDetail>mapToObj(index -> row(index, 7, "날", null))
                .toList();
        found(rows);
        when(storage.createReadUrl(anyString())).thenAnswer(call -> "https://image.test/" + call.getArgument(0));

        StoryDetailResponse response = service.findStory(42L, 7L);

        assertThat(response.days()).hasSize(1);
        assertThat(response.days().getFirst().blocks()).hasSize(30);
        assertThat(response.days().getFirst().blocks().getLast().orderNumber()).isEqualTo(30);
        verify(storage, times(30)).createReadUrl(anyString());
    }

    @Test
    void 모든_표시검증이_완료된_후_URL을_발급한다() {
        StoryValidatorTest.Row invalid = row(2, 7, "날", null);
        ReflectionTestUtils.setField(invalid.attachment(), "previewStorageKey", " ");
        found(List.of(row(1, 7, "날", null), invalid));
        assertThatThrownBy(() -> service.findStory(42L, 7L)).isInstanceOf(StoryDataIntegrityException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void 권한이_없으면_스토리와_URL을_조회하지_않는다() {
        doThrow(new TripNotFoundException()).when(access).requireReadPermission(7L, 42L, 42L);
        assertThatThrownBy(() -> service.findStory(42L, 7L)).isInstanceOf(StoryNotFoundException.class);
        verifyNoInteractions(contents, references, storage);
    }

    @Test
    void 현재_완료_스토리가_없으면_404다() {
        when(stories.findReadContextByTripId(7L)).thenReturn(Optional.of(
                new StoryReadContext(7L, 42L, null)));
        assertThatThrownBy(() -> service.findStory(42L, 7L)).isInstanceOf(StoryNotFoundException.class);
        verifyNoInteractions(contents, references, storage);
    }

    @Test
    void 스토리지와_DB_오류를_404로_변환하지_않는다() {
        found(List.of(row(1, 7, "날", null)));
        when(storage.createReadUrl("preview/1")).thenThrow(new IllegalStateException(
                StoryGenerationInternalErrorMessage.READ_DATA_INTEGRITY_FAILED.message()
        ));
        assertThatThrownBy(() -> service.findStory(42L, 7L)).isInstanceOf(IllegalStateException.class);
        doThrow(new DataAccessResourceFailureException(
                StoryGenerationInternalErrorMessage.READ_DATA_INTEGRITY_FAILED.message()
        )).when(access).requireReadPermission(7L, 42L, 42L);
        assertThatThrownBy(() -> service.findStory(42L, 7L)).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
