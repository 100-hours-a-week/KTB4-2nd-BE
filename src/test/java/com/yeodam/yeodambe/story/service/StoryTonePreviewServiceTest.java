package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.InvalidStoryTonePreviewException;
import com.yeodam.yeodambe.common.exception.RunpodStatusUnavailableException;
import com.yeodam.yeodambe.common.exception.StoryTonePreviewFailedException;
import com.yeodam.yeodambe.common.exception.StoryTonePreviewUnavailableException;
import com.yeodam.yeodambe.integration.service.RunpodStatusService;
import com.yeodam.yeodambe.integration.service.response.RunpodStatusResponse;
import com.yeodam.yeodambe.story.client.StoryTonePreviewAiClient;
import com.yeodam.yeodambe.story.client.request.StoryTonePreviewAiRequest;
import com.yeodam.yeodambe.story.client.response.StoryTonePreviewAiResponse;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.service.request.StoryTonePreviewRequest;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoryTonePreviewServiceTest {
    private final StoryTonePreviewInputService input = mock(StoryTonePreviewInputService.class);
    private final RunpodStatusService ready = mock(RunpodStatusService.class);
    private final StoryTonePreviewAiClient ai = mock(StoryTonePreviewAiClient.class);
    private final StoryTonePreviewResultValidator validator = spy(new StoryTonePreviewResultValidator());
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final StoryTonePreviewService service = new StoryTonePreviewService(input, ready, ai, validator, storage, 2);
    private final StoryTonePreviewRequest frontend = new StoryTonePreviewRequest(Story.Mood.PLAIN, List.of());

    @Test
    void 준비_확인_호출_검증_순서대로_실행하고_기존신규블록을_날짜별로_조립한다() {
        var prepared = prepared();
        var response = response();
        when(input.prepare(2L, 9L, frontend)).thenReturn(prepared);
        when(ready.getStatus()).thenReturn(new RunpodStatusResponse(true, true));
        when(ai.preview(7L, prepared.request())).thenReturn(response);
        when(storage.createReadUrl("preview/101")).thenReturn("https://example.test/101");
        when(storage.createReadUrl("preview/102")).thenReturn("https://example.test/102");
        when(storage.createReadUrl("preview/103")).thenReturn("https://example.test/103");

        var actual = service.preview(2L, 9L, frontend);

        var order = inOrder(input, ready, ai, validator, storage);
        order.verify(input).prepare(2L, 9L, frontend);
        order.verify(ready).getStatus();
        order.verify(ai).preview(7L, prepared.request());
        order.verify(validator).validate(prepared.request(), response);
        order.verify(storage).createReadUrl("preview/101");
        assertThat(actual.storyId()).isEqualTo(9L);
        assertThat(actual.tone()).isEqualTo(Story.Mood.PLAIN);
        assertThat(actual.storySummary()).isEqualTo("새 요약");
        assertThat(actual.days()).hasSize(2);
        assertThat(actual.days().getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(actual.days().getFirst().blocks()).hasSize(2);
        var first = actual.days().getFirst().blocks().getFirst();
        assertThat(first.storyBlockId()).isEqualTo(11L);
        assertThat(first.tripPlaceId()).isEqualTo(501L);
        assertThat(first.tripAttachmentId()).isEqualTo(101L);
        assertThat(first.thumbnailUrl()).isEqualTo("https://example.test/101");
        assertThat(first.detailSummary()).isEqualTo("새 캡션101");
        assertThat(first.memo()).isNull();
        assertThat(actual.days().get(1).blocks().getFirst().storyBlockId()).isNull();
        assertThat(actual.days().get(1).blocks().getFirst().orderNumber()).isEqualTo(3);
        assertThatThrownBy(() -> actual.days().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> actual.days().getFirst().blocks().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 입력검증이_실패하면_AI상태확인과_호출을_하지않는다() {
        when(input.prepare(2L, 9L, frontend)).thenThrow(new InvalidStoryTonePreviewException());
        assertThatThrownBy(() -> service.preview(2L, 9L, frontend))
                .isInstanceOf(InvalidStoryTonePreviewException.class);
        verifyNoInteractions(ready, ai, validator, storage);
    }

    @Test
    void 준비되지않았거나_준비조회실패면_미리보기이용불가로_거절한다() {
        var prepared = prepared();
        when(input.prepare(2L, 9L, frontend)).thenReturn(prepared);
        when(ready.getStatus()).thenReturn(new RunpodStatusResponse(false, false));
        assertThatThrownBy(() -> service.preview(2L, 9L, frontend))
                .isInstanceOf(StoryTonePreviewUnavailableException.class);
        when(ready.getStatus()).thenThrow(new RunpodStatusUnavailableException());
        assertThatThrownBy(() -> service.preview(2L, 9L, frontend))
                .isInstanceOf(StoryTonePreviewUnavailableException.class);
        verifyNoInteractions(ai, validator, storage);
    }

    @Test
    void AI결과가_잘못되면_썸네일발급과_응답조립전에_거절한다() {
        var prepared = prepared();
        when(input.prepare(2L, 9L, frontend)).thenReturn(prepared);
        when(ready.getStatus()).thenReturn(new RunpodStatusResponse(true, true));
        when(ai.preview(7L, prepared.request())).thenReturn(new StoryTonePreviewAiResponse("요약", List.of()));
        assertThatThrownBy(() -> service.preview(2L, 9L, frontend))
                .isInstanceOf(StoryTonePreviewFailedException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void 같은날짜여도_라벨이달라지면_순서를유지하며_묶음을나눈다() {
        var prepared = prepared();
        when(input.prepare(2L, 9L, frontend)).thenReturn(prepared);
        when(ready.getStatus()).thenReturn(new RunpodStatusResponse(true, true));
        when(ai.preview(7L, prepared.request())).thenReturn(new StoryTonePreviewAiResponse("요약", List.of(
                new StoryTonePreviewAiResponse.Block(1, 101L, "첫 장소", "글", null),
                new StoryTonePreviewAiResponse.Block(2, 102L, "다음 장소", "글", null),
                new StoryTonePreviewAiResponse.Block(3, 103L, "둘째 날", "글", null))));
        var actual = service.preview(2L, 9L, frontend);
        assertThat(actual.days()).extracting(day -> day.dayLabel())
                .containsExactly("첫 장소", "다음 장소", "둘째 날");
    }

    private StoryTonePreviewInputService.PreparedRequest prepared() {
        var blocks = List.of(
                new StoryTonePreviewAiRequest.Block(1, 101L, "장소", null, "key", null, null, null),
                new StoryTonePreviewAiRequest.Block(2, 102L, "장소", null, "key", null, null, null),
                new StoryTonePreviewAiRequest.Block(3, 103L, "장소", null, "key", null, null, null));
        return new StoryTonePreviewInputService.PreparedRequest(7L,
                new StoryTonePreviewAiRequest("여행", null, Story.Mood.PLAIN, blocks), List.of(
                new StoryTonePreviewInputService.BlockSource(11L, 1, 101L, 501L,
                        LocalDate.of(2026, 10, 1), "preview/101"),
                new StoryTonePreviewInputService.BlockSource(12L, 2, 102L, 501L,
                        LocalDate.of(2026, 10, 1), "preview/102"),
                new StoryTonePreviewInputService.BlockSource(null, 3, 103L, 502L,
                        LocalDate.of(2026, 10, 2), "preview/103")));
    }

    private StoryTonePreviewAiResponse response() {
        return new StoryTonePreviewAiResponse("새 요약", List.of(
                new StoryTonePreviewAiResponse.Block(1, 101L, "첫째 날", "새 캡션101", null),
                new StoryTonePreviewAiResponse.Block(2, 102L, "첫째 날", "새 캡션102", null),
                new StoryTonePreviewAiResponse.Block(3, 103L, "둘째 날", "새 캡션103", null)));
    }
}
