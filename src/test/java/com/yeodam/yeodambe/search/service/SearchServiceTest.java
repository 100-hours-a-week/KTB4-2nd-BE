package com.yeodam.yeodambe.search.service;

import com.yeodam.yeodambe.integration.service.AiQueryService;
import com.yeodam.yeodambe.common.exception.AiQueryUnavailableException;
import com.yeodam.yeodambe.integration.service.request.AiQuerySearchRequest;
import com.yeodam.yeodambe.integration.service.response.AiQueryParseResponse;
import com.yeodam.yeodambe.integration.service.response.AiQuerySearchResponse;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.response.TripSearchResultResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.LongStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchServiceTest {
    private final AiQueryService ai = mock(AiQueryService.class);
    private final TripService trips = mock(TripService.class);
    private final SearchService service = new SearchService(trips, ai);

    @BeforeEach
    void setUp() {
        when(ai.parse("바다 사진")).thenReturn(new AiQueryParseResponse(
                AiQueryParseResponse.Intent.QUESTION, null, null, List.of(), "바다"));
    }

    @Test
    void 검색_후보가_없으면_AI_검색과_결과_조회를_생략한다() {
        when(trips.findSearchCandidateIds(eq(1L), any())).thenReturn(List.of());
        var result = service.search(1L, "바다 사진");
        assertThat(result.attachments()).isEmpty();
        assertThat(result.folders()).isEmpty();
        assertThat(result.answer()).isNull();
        verify(ai, never()).search(any());
        verify(trips, never()).findSearchResults(anyLong(), any());
    }

    @Test
    void 결과_개수_제한_전에_검색_후보에_없는_사진을_거부한다() {
        when(trips.findSearchCandidateIds(eq(1L), any())).thenReturn(List.of(1L, 2L));
        when(ai.search(any())).thenReturn(new AiQuerySearchResponse(
                List.of(hit(1, .9), hit(3, .1)), null, null));
        assertThatThrownBy(() -> service.search(1L, "바다 사진"))
                .isInstanceOf(AiQueryUnavailableException.class);
    }

    @Test
    void 중복_사진을_최고_점수로_병합하고_동점은_ID순으로_정렬한다() {
        prepare(List.of(hit(2, .9), hit(1, .2), hit(1, .9)), List.of(photo(2, 2), photo(1, 1)),
                List.of(folder(2), folder(1)), "답변", null);
        var result = service.search(1L, "바다 사진");
        assertThat(result.attachments()).extracting(item -> item.tripAttachmentId()).containsExactly(1L, 2L);
        assertThat(result.attachments()).extracting(item -> item.score()).containsExactly(.9, .9);
        assertThat(result.folders()).extracting(item -> item.tripId()).containsExactly(1L, 2L);
        assertThat(result.answer()).isEqualTo("답변");
        verify(ai).search(new AiQuerySearchRequest(AiQueryParseResponse.Intent.QUESTION,
                "바다", List.of(1L, 2L), 30));
    }

    @Test
    void 근거_사진이_사라지면_답변을_제외하고_답변_오류는_유지한다() {
        prepare(List.of(hit(1, .8), hit(2, .9)), List.of(photo(1, 1)),
                List.of(folder(1)), "답변", "AI_WARNING");
        var result = service.search(1L, "바다 사진");
        assertThat(result.answer()).isNull();
        assertThat(result.answerError()).isEqualTo("AI_WARNING");
        assertThat(result.attachments()).extracting(item -> item.tripAttachmentId()).containsExactly(1L);
    }

    @Test
    void 사진과_폴더_개수를_제한하고_같은_여행의_사진을_하나의_폴더로_묶는다() {
        List<Long> ids = LongStream.rangeClosed(1, 35).boxed().toList();
        List<AiQuerySearchResponse.Attachment> hits = ids.stream().map(id -> hit(id, .5)).toList();
        List<TripSearchResultResponse.Attachment> photos = ids.stream()
                .map(id -> photo(id, id <= 2 ? 1 : id)).toList();
        List<TripSearchResultResponse.Folder> folders = ids.stream().map(this::folder).toList();
        prepare(hits, photos, folders, null, "ANSWER_FAILED");
        when(trips.findSearchCandidateIds(eq(1L), any())).thenReturn(ids);
        var result = service.search(1L, "바다 사진");
        assertThat(result.attachments()).hasSize(30);
        assertThat(result.attachments().getLast().tripAttachmentId()).isEqualTo(30L);
        assertThat(result.folders()).hasSize(30);
        assertThat(result.folders()).extracting(item -> item.tripId())
                .containsExactly(1L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L,
                        12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L, 21L,
                        22L, 23L, 24L, 25L, 26L, 27L, 28L, 29L, 30L, 31L);
        assertThat(result.answerError()).isEqualTo("ANSWER_FAILED");
    }

    @Test
    void 사진_개수_제한_전_여행별_최고_사진_점수로_폴더를_정렬한다() {
        prepare(List.of(hit(1, .4), hit(2, .8), hit(3, .9)),
                List.of(photo(1, 1), photo(2, 1), photo(3, 2)),
                List.of(folder(1), folder(2)), null, null);
        var result = service.search(1L, "바다 사진");
        assertThat(result.folders()).extracting(item -> item.tripId()).containsExactly(2L, 1L);
        assertThat(result.folders()).extracting(item -> item.score()).containsExactly(.9, .8);
        assertThat(result.attachments()).extracting(item -> item.tripAttachmentId()).containsExactly(3L, 2L, 1L);
    }

    @Test
    void 공개_사진_개수_제한에서_제외된_사진의_폴더도_유지한다() {
        List<Long> ids = LongStream.rangeClosed(1, 31).boxed().toList();
        prepare(ids.stream().map(id -> hit(id, id <= 30 ? .9 : .1)).toList(),
                ids.stream().map(id -> photo(id, id <= 30 ? 1 : 2)).toList(),
                List.of(folder(1), folder(2)), null, null);
        var result = service.search(1L, "바다 사진");
        assertThat(result.attachments()).hasSize(30);
        assertThat(result.folders()).extracting(item -> item.tripId()).containsExactly(1L, 2L);
    }

    private void prepare(List<AiQuerySearchResponse.Attachment> hits,
            List<TripSearchResultResponse.Attachment> photos, List<TripSearchResultResponse.Folder> folders,
            String answer, String error) {
        when(trips.findSearchCandidateIds(eq(1L), any()))
                .thenReturn(hits.stream().map(AiQuerySearchResponse.Attachment::tripAttachmentId).distinct().sorted().toList());
        when(ai.search(any())).thenReturn(new AiQuerySearchResponse(hits, answer, error));
        when(trips.findSearchResults(eq(1L), any())).thenReturn(new TripSearchResultResponse(photos, folders));
    }

    private AiQuerySearchResponse.Attachment hit(long id, double score) {
        return new AiQuerySearchResponse.Attachment(id, score);
    }

    private TripSearchResultResponse.Attachment photo(long id, long tripId) {
        return new TripSearchResultResponse.Attachment(id, tripId, id + 100, "photo-url");
    }

    private TripSearchResultResponse.Folder folder(long id) {
        return new TripSearchResultResponse.Folder(id, "여행", LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 1, 2), List.of("제주"), 50, "trip-url");
    }
}
