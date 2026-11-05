package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.common.exception.*;
import com.yeodam.yeodambe.story.repository.*;
import com.yeodam.yeodambe.story.repository.StoryBlockRepository.BlockDetail;
import com.yeodam.yeodambe.story.service.response.StoryDetailResponse;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
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
    TripAttachmentRepository attachments;

    @Mock
    TripDetailPlaceRepository places;

    @Mock
    TripAttachmentStorageClient storage;
    @Mock
    StoryContentRepository contents;
    StoryReadService service;

    @BeforeEach
    void setup() {
        service = new StoryReadService(access, stories, new StoryValidator(), storage, contents, attachments, places);
        lenient().when(access.requireReadableTrip(7L, 42L))
                .thenReturn(new Trip(42L, "여행", LocalDate.now(), LocalDate.now()));
    }

    private void found(List<BlockDetail> rows) {
        Story story = new Story(7L, Story.Mood.EMOTIONAL, "여행 요약");
        ReflectionTestUtils.setField(story, "id", 1L);
        when(stories.findCurrentCompletedByTripId(7L)).thenReturn(Optional.of(story));
        List<StoryContentBlock> documentBlocks = new ArrayList<>();
        Map<Long, com.yeodam.yeodambe.trip.entity.TripAttachment> photos = new LinkedHashMap<>();
        Map<Long, com.yeodam.yeodambe.trip.entity.TripDetailPlace> sourcePlaces = new LinkedHashMap<>();
        for (BlockDetail row : rows) {
            var block = row.getBlock();
            documentBlocks.add(new StoryContentBlock(
                    block.getId(), block.getTripAttachmentId(), block.getSourceTripPlaceId(),
                    block.getSourceDate(), block.getDayLabel(), block.getDetailSummary(), block.getMemo(),
                    null, null, block.getDeletedAt() == null ? null
                            : block.getDeletedAt().toInstant(java.time.ZoneOffset.UTC)));
            if (row.getAttachment() != null) {
                ReflectionTestUtils.setField(row.getAttachment(), "file", row.getFile());
                photos.put(row.getAttachment().getId(), row.getAttachment());
            }
            if (row.getPlace() != null) {
                sourcePlaces.put(row.getPlace().getId(), row.getPlace());
            }
        }
        when(contents.findById(1L)).thenReturn(Optional.of(new StoryContentDocument(
                1L, story.getMood(), story.getStorySummary(), List.of(), documentBlocks, 100L)));
        lenient().when(attachments.findAllWithFileByIds(anyCollection()))
                .thenReturn(new ArrayList<>(photos.values()));
        lenient().when(places.findAllById(anyIterable()))
                .thenReturn(new ArrayList<>(sourcePlaces.values()));
    }

    @Test
    void 분위기와_요약은_MySQL이_아닌_MongoDB_본문을_사용한다() {
        found(List.of());
        when(contents.findById(1L)).thenReturn(Optional.of(new StoryContentDocument(
                1L, Story.Mood.CALM, "문서의 요약", List.of(), List.of(), 1L)));

        var response = service.findStory(42L, 7L);

        assertThat(response.mood()).isEqualTo(Story.Mood.CALM);
        assertThat(response.storySummary()).isEqualTo("문서의 요약");
        verifyNoInteractions(attachments, places, storage);
    }

    @Test
    void 완료된_스토리의_문서가_없으면_정합성_오류다() {
        found(List.of());
        when(contents.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findStory(42L, 7L))
                .isInstanceOf(StoryDataIntegrityException.class)
                .hasMessage("완료된 스토리의 MongoDB 본문이 없습니다.");
        verifyNoInteractions(attachments, places, storage);
    }

    @Test
    void 사진_조회순서와_무관하게_문서_배열순서와_블록ID를_유지한다() {
        var first = row(9, 7, "날", null);
        var second = row(3, 7, "날", null);
        found(List.of(first, second));
        when(attachments.findAllWithFileByIds(anyCollection()))
                .thenReturn(List.of(second.attachment(), first.attachment()));
        when(storage.createReadUrl(anyString())).thenAnswer(call -> "url/" + call.getArgument(0));

        var blocks = service.findStory(42L, 7L).days().getFirst().blocks();

        assertThat(blocks).extracting(StoryDetailResponse.Block::storyBlockId).containsExactly(9L, 3L);
        assertThat(blocks).extracting(StoryDetailResponse.Block::tripAttachmentId).containsExactly(9L, 3L);
        assertThat(blocks).extracting(StoryDetailResponse.Block::orderNumber).containsExactly(1, 2);
        verify(attachments).findAllWithFileByIds(List.of(9L, 3L));
        verify(places).findAllById(List.of(7L, 7L));
    }

    @Test
    void 삭제된_문서블록은_사진조회에서_제외하고_삭제사진은_순서만_남긴다() {
        var removed = row(1, 7, "날", null);
        removed.block().softDelete(LocalDateTime.now());
        var deletedPhoto = row(2, 7, "날", null);
        deletedPhoto.attachment().softDelete(LocalDateTime.now());
        found(List.of(removed, deletedPhoto, row(3, 7, "날", null)));
        when(storage.createReadUrl("preview/3")).thenReturn("url/3");

        var blocks = service.findStory(42L, 7L).days().getFirst().blocks();

        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst().storyBlockId()).isEqualTo(3L);
        assertThat(blocks.getFirst().orderNumber()).isEqualTo(2);
        verify(attachments).findAllWithFileByIds(List.of(2L, 3L));
        verify(storage).createReadUrl("preview/3");
        verifyNoMoreInteractions(storage);
    }

    @Test
    void 참조된_사진이_없으면_URL발급전에_거절한다() {
        var source = row(1, 7, "날", null);
        found(List.of(new StoryValidatorTest.Row(source.block(), null, source.file(), source.place())));

        assertThatThrownBy(() -> service.findStory(42L, 7L))
                .isInstanceOf(StoryDataIntegrityException.class)
                .hasMessage("블록 관계: 첨부가 없습니다.");
        verifyNoInteractions(storage);
    }

    @Test
    void 파일이_없는_사진도_URL발급전에_거절한다() {
        var source = row(1, 7, "날", null);
        found(List.of(new StoryValidatorTest.Row(source.block(), source.attachment(), null, source.place())));

        assertThatThrownBy(() -> service.findStory(42L, 7L))
                .isInstanceOf(StoryDataIntegrityException.class)
                .hasMessage("블록 관계: 파일이 없습니다.");
        verifyNoInteractions(storage);
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
        verify(attachments, never()).save(any());
    }

    @Test
    void 공유_사용자는_스토리를_조회하되_소유자로_표시하지_않는다() {
        when(access.requireReadableTrip(7L, 42L))
                .thenReturn(new Trip(1L, "공유 여행", LocalDate.now(), LocalDate.now()));
        found(List.of());

        assertThat(service.findStory(42L, 7L).userByMe()).isFalse();
        verify(access).requireReadableTrip(7L, 42L);
        verify(access, never()).requireOwnedTrip(anyLong(), anyLong());
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
        found(Collections.nCopies(31, deleted));
        assertThatThrownBy(() -> service.findStory(42L, 7L)).isInstanceOf(StoryDataIntegrityException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void 생성_계약의_최대_30개_블록을_상세_응답으로_반환한다() {
        var rows = java.util.stream.IntStream.rangeClosed(1, 30)
                .<BlockDetail>mapToObj(index -> row(index, 7, "날", null))
                .toList();
        found(rows);
        when(storage.createReadUrl(anyString())).thenAnswer(call -> "https://image.test/" + call.getArgument(0));

        var response = service.findStory(42L, 7L);

        assertThat(response.days()).hasSize(1);
        assertThat(response.days().getFirst().blocks()).hasSize(30);
        assertThat(response.days().getFirst().blocks().getLast().orderNumber()).isEqualTo(30);
        verify(storage, times(30)).createReadUrl(anyString());
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
        verifyNoInteractions(stories, contents, attachments, places, storage);
    }

    @Test
    void 현재_완료_스토리가_없으면_404다() {
        when(stories.findCurrentCompletedByTripId(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findStory(42L, 7L)).isInstanceOf(StoryNotFoundException.class);
        verifyNoInteractions(contents, attachments, places, storage);
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
