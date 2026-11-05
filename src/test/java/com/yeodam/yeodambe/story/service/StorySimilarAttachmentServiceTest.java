package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryBlockNotFoundException;
import com.yeodam.yeodambe.story.client.StorySimilarAttachmentAiClient;
import com.yeodam.yeodambe.story.client.request.StorySimilarAttachmentAiRequest;
import com.yeodam.yeodambe.story.client.response.StorySimilarAttachmentAiResponse;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.story.exception.StorySimilarAttachmentAiRequestException;
import com.yeodam.yeodambe.story.exception.StorySimilarAttachmentInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import com.yeodam.yeodambe.story.service.response.StorySimilarAttachmentListResponse;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.RegionOrigin;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StorySimilarAttachmentServiceTest {
    private final StoryRepository stories = mock(StoryRepository.class);
    private final StoryContentRepository contents = mock(StoryContentRepository.class);
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final StorySimilarAttachmentAiClient aiClient = mock(StorySimilarAttachmentAiClient.class);
    private final StorySimilarAttachmentService service =
            new StorySimilarAttachmentService(stories, contents, attachments, aiClient);
    private final StorySimilarAttachmentAiRequest expectedRequest =
            new StorySimilarAttachmentAiRequest(501L, List.of(506L, 507L), 10);
    private final TripAttachment source = attachment(501L, 31L);

    @BeforeEach
    void setUp() {
        Story story = new Story(31L, Story.Mood.PLAIN, "요약");
        ReflectionTestUtils.setField(story, "id", 71L);
        when(stories.findOwnedCurrentCompletedForSimilarById(71L, 1L))
                .thenReturn(Optional.of(story));
        arrangeContent(List.of(block(101L, source), block(102L, attachment(502L, 31L))));
        when(attachments.findAllActiveWithTripAndFileByIds(List.of(501L)))
                .thenReturn(List.of(source));
        var candidates = List.of(source, attachment(502L, 31L), attachment(506L, 31L),
                attachment(507L, 31L), attachment(777L, 99L));
        when(attachments.findAllActiveByTripPlaceId(11L, ClassificationStatus.ACTIVE))
                .thenReturn(candidates);
        when(aiClient.findSimilar(31L, expectedRequest)).thenReturn(new StorySimilarAttachmentAiResponse(501L,
                List.of(item(507L, 0.94), item(506L, 0.91))));
    }

    @Test
    void 현재_폴더에서_스토리_사용사진과_다른여행_사진을_제외하고_AI_순서대로_반환한다() {
        var actual = service.findSimilar(1L, 71L, 101L);

        verify(attachments).findAllActiveByTripPlaceId(11L, ClassificationStatus.ACTIVE);
        verify(aiClient).findSimilar(31L, expectedRequest);
        assertThat(actual.sourceTripAttachmentId()).isEqualTo(501L);
        assertThat(actual.items()).containsExactly(
                new StorySimilarAttachmentListResponse.Item(507L, 0.94),
                new StorySimilarAttachmentListResponse.Item(506L, 0.91));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 후보가_없거나_모두_사용중이면_AI를_호출하지_않는다(boolean onlyUsed) {
        List<TripAttachment> candidates = onlyUsed
                ? List.of(source, attachment(502L, 31L)) : List.of();
        when(attachments.findAllActiveByTripPlaceId(11L, ClassificationStatus.ACTIVE))
                .thenReturn(candidates);

        var actual = service.findSimilar(1L, 71L, 101L);

        assertThat(actual).isEqualTo(new StorySimilarAttachmentListResponse(501L, List.of()));
        verifyNoInteractions(aiClient);
    }

    @Test
    void 기준_블록에_접근할_수_없으면_후보조회와_AI호출_전에_중단한다() {
        when(stories.findOwnedCurrentCompletedForSimilarById(71L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findSimilar(1L, 71L, 101L))
                .isInstanceOf(StoryBlockNotFoundException.class);

        verifyNoInteractions(attachments, aiClient);
        verifyNoInteractions(contents);
    }

    @Test
    void AI가_빈_추천을_반환하면_정상_빈목록으로_전달한다() {
        when(aiClient.findSimilar(31L, expectedRequest))
                .thenReturn(new StorySimilarAttachmentAiResponse(501L, List.of()));

        assertThat(service.findSimilar(1L, 71L, 101L).items()).isEmpty();
    }

    @Test
    void AI_통신_실패를_정상_빈목록으로_숨기지_않는다() {
        var failure = new StorySimilarAttachmentAiRequestException(
                StorySimilarAttachmentInternalErrorMessage.AI_REQUEST_FAILED);
        when(aiClient.findSimilar(31L, expectedRequest)).thenThrow(failure);

        assertThatThrownBy(() -> service.findSimilar(1L, 71L, 101L)).isSameAs(failure);
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void 후보_범위나_필수값이_잘못된_AI_결과는_응답하지_않는다(StorySimilarAttachmentAiResponse response) {
        when(aiClient.findSimilar(31L, expectedRequest)).thenReturn(response);

        assertThatThrownBy(() -> service.findSimilar(1L, 71L, 101L))
                .isInstanceOf(StorySimilarAttachmentAiRequestException.class)
                .hasMessage(StorySimilarAttachmentInternalErrorMessage.AI_RESPONSE_INVALID.message());
    }

    static Stream<StorySimilarAttachmentAiResponse> invalidResponses() {
        List<StorySimilarAttachmentAiResponse> cases = new ArrayList<>();
        cases.add(new StorySimilarAttachmentAiResponse(null, List.of()));
        cases.add(new StorySimilarAttachmentAiResponse(999L, List.of()));
        cases.add(new StorySimilarAttachmentAiResponse(501L, null));
        cases.add(new StorySimilarAttachmentAiResponse(501L, Arrays.asList((StorySimilarAttachmentAiResponse.Item) null)));
        cases.add(new StorySimilarAttachmentAiResponse(501L, List.of(item(null, 0.9))));
        cases.add(new StorySimilarAttachmentAiResponse(501L, List.of(item(506L, null))));
        cases.add(new StorySimilarAttachmentAiResponse(501L, List.of(item(506L, Double.NaN))));
        cases.add(new StorySimilarAttachmentAiResponse(501L, List.of(item(506L, Double.POSITIVE_INFINITY))));
        cases.add(new StorySimilarAttachmentAiResponse(501L, List.of(item(999L, 0.9))));
        cases.add(new StorySimilarAttachmentAiResponse(501L, List.of(item(501L, 0.9))));
        cases.add(new StorySimilarAttachmentAiResponse(501L, List.of(item(502L, 0.9))));
        cases.add(new StorySimilarAttachmentAiResponse(501L, List.of(item(506L, 0.9), item(506L, 0.8))));
        cases.add(new StorySimilarAttachmentAiResponse(501L,
                java.util.Collections.nCopies(11, item(506L, 0.9))));
        return cases.stream();
    }

    private static StorySimilarAttachmentAiResponse.Item item(Long id, Double score) {
        return new StorySimilarAttachmentAiResponse.Item(id, score);
    }

    private static TripAttachment attachment(Long id, Long tripId) {
        TripAttachment attachment = TripAttachment.initial(tripId, id + 1000, "analyze", "preview");
        ReflectionTestUtils.setField(attachment, "id", id);
        attachment.classify(11L, RegionOrigin.EXIF, LocalDateTime.of(2026, 10, 30, 12, 0),
                BigDecimal.ZERO, BigDecimal.ZERO, 1);
        TripDetailPlace place = mock(TripDetailPlace.class);
        when(place.getTripId()).thenReturn(tripId);
        ReflectionTestUtils.setField(attachment, "tripPlace", place);
        return attachment;
    }

    private static StoryContentBlock block(Long id, TripAttachment attachment) {
        return new StoryContentBlock(id, attachment.getId(), 91L,
                LocalDate.of(2026, 10, 29), "첫날", "산책", null, null, null, null);
    }

    private void arrangeContent(List<StoryContentBlock> blocks) {
        when(contents.findById(71L)).thenReturn(Optional.of(new StoryContentDocument(
                71L, Story.Mood.PLAIN, "요약", List.of(91L), blocks, 200L)));
    }

    @Test
    void Mongo본문이_없으면_사진과_AI를_조회하지않는다() {
        when(contents.findById(71L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findSimilar(1L, 71L, 101L))
                .isInstanceOf(StoryDataIntegrityException.class);
        verifyNoInteractions(attachments, aiClient);
    }

    @Test
    void 삭제된_Mongo블록은_조회할수없다() {
        arrangeContent(List.of(new StoryContentBlock(101L, 501L, 91L,
                LocalDate.of(2026, 10, 29), "날", "문구", null, null, null,
                java.time.Instant.parse("2026-11-01T00:00:00Z"))));
        assertThatThrownBy(() -> service.findSimilar(1L, 71L, 101L))
                .isInstanceOf(StoryBlockNotFoundException.class);
        verifyNoInteractions(attachments, aiClient);
    }

    @Test
    void 삭제된블록의_사진은_후보에서_제외하지않는다() {
        arrangeContent(List.of(block(101L, source), new StoryContentBlock(
                102L, 502L, 91L, LocalDate.of(2026, 10, 29), "날", "문구", null,
                null, null, java.time.Instant.parse("2026-11-01T00:00:00Z"))));
        var request = new StorySimilarAttachmentAiRequest(501L, List.of(502L, 506L, 507L), 10);
        when(aiClient.findSimilar(31L, request)).thenReturn(
                new StorySimilarAttachmentAiResponse(501L, List.of(item(502L, 0.99))));
        assertThat(service.findSimilar(1L, 71L, 101L).items()).containsExactly(
                new StorySimilarAttachmentListResponse.Item(502L, 0.99));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-photo", "other-trip", "unclassified", "missing-place", "deleted-place", "other-trip-place"})
    void 사용할수없는_기준사진은_AI호출전에_거절한다(String condition) {
        switch (condition) {
            case "missing-photo" -> when(attachments.findAllActiveWithTripAndFileByIds(List.of(501L)))
                    .thenReturn(List.of());
            case "other-trip" -> ReflectionTestUtils.setField(source, "tripId", 99L);
            case "unclassified" -> ReflectionTestUtils.setField(source, "classificationStatus", ClassificationStatus.UNCLASSIFIED);
            case "missing-place" -> ReflectionTestUtils.setField(source, "tripPlace", null);
            case "deleted-place" -> when(source.getTripPlace().getDeletedAt()).thenReturn(LocalDateTime.now());
            case "other-trip-place" -> when(source.getTripPlace().getTripId()).thenReturn(99L);
        }
        assertThatThrownBy(() -> service.findSimilar(1L, 71L, 101L))
                .isInstanceOf(StoryBlockNotFoundException.class);
        verifyNoInteractions(aiClient);
    }
}
