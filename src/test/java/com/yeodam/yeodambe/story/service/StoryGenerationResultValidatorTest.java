package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.client.request.StoryGenerationMessage;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse.Block;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse.Result;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoryGenerationResultValidatorTest {
    private final StoryGenerationResultValidator validator = new StoryGenerationResultValidator();

    @Test
    void 이모지가_포함된_최대_길이의_모든_스토리_문구를_허용한다() {
        String text = "가".repeat(39) + "😀";
        String memo = "나".repeat(124) + "😀";
        Result result = new Result(text, List.of(new Block(1, 11L, 21L, text, text, memo)));

        assertThatCode(() -> validator.validate(result)).doesNotThrowAnyException();
    }

    @Test
    void 코드_포인트_제한을_초과한_스토리_문구를_거부한다() {
        String text = "😀".repeat(41);
        Block validBlock = block(1, 11L, 21L);
        assertInvalid(new Result(text, List.of(validBlock)));
        assertInvalid(new Result("요약", List.of(new Block(1, 11L, 21L, text, "설명", null))));
        assertInvalid(new Result("요약", List.of(new Block(1, 11L, 21L, "첫날", text, null))));
        assertInvalid(new Result("요약", List.of(new Block(1, 11L, 21L, "첫날", "설명", "😀".repeat(126)))));
    }

    @Test
    void 블록_30개와_최대_길이_텍스트를_허용한다() {
        var blocks = IntStream.rangeClosed(1, 30)
                .mapToObj(index -> new Block(index, 11L, (long) index,
                        "가".repeat(40), "나".repeat(40), "다".repeat(125)))
                .toList();

        assertThatCode(() -> validator.validate(new Result("가".repeat(40), blocks)))
                .doesNotThrowAnyException();
    }

    @Test
    void 블록_31개를_거부한다() {
        var blocks = IntStream.rangeClosed(1, 31)
                .mapToObj(index -> block(index, 11L, (long) index))
                .toList();

        assertInvalid(new Result("요약", blocks));
    }

    @ParameterizedTest
    @MethodSource("invalidResults")
    void 결과나_요약이나_블록이_없으면_거부한다(Result result) {
        assertInvalid(result);
    }

    static Stream<Result> invalidResults() {
        return Stream.of(null, new Result(null, List.of(block(1, 11L, 21L))),
                new Result(" ", List.of(block(1, 11L, 21L))),
                new Result("가".repeat(41), List.of(block(1, 11L, 21L))),
                new Result("요약", null), new Result("요약", List.of()));
    }

    @ParameterizedTest
    @MethodSource("invalidBlocks")
    void 유효하지_않은_블록_필드를_거부한다(Block block) {
        assertInvalid(new Result("요약", Arrays.asList(block)));
    }

    static Stream<Block> invalidBlocks() {
        return Stream.of(null, block(null, 11L, 21L), block(0, 11L, 21L),
                block(1, null, 21L), block(1, 0L, 21L), block(1, 11L, null), block(1, 11L, 0L),
                new Block(1, 11L, 21L, null, "설명", null),
                new Block(1, 11L, 21L, " ", "설명", null),
                new Block(1, 11L, 21L, "가".repeat(41), "설명", null),
                new Block(1, 11L, 21L, "첫날", null, null),
                new Block(1, 11L, 21L, "첫날", " ", null),
                new Block(1, 11L, 21L, "첫날", "가".repeat(41), null),
                new Block(1, 11L, 21L, "첫날", "설명", "가".repeat(126)));
    }

    @Test
    void 중복된_순서_번호를_거부한다() {
        assertInvalid(new Result("요약", List.of(block(1, 11L, 21L), block(1, 11L, 22L))));
    }

    @Test
    void 순서가_달라도_중복된_사진을_거부한다() {
        assertInvalid(new Result("요약", List.of(block(1, 11L, 21L), block(2, 11L, 21L))));
    }

    @Test
    void 모든_입력_사진을_요구하지_않고_여러_장소에서_선택한_사진을_허용한다() {
        var result = new Result("요약", List.of(block(1, 11L, 21L), block(2, 12L, 23L)));

        assertThatCode(() -> validator.validate(result, request())).doesNotThrowAnyException();
    }

    @Test
    void 전송하지_않은_사진을_거부한다() {
        assertInvalidReferences(new Result("요약", List.of(block(1, 11L, 99L))), request());
    }

    @Test
    void 두_장소를_모두_전송했어도_사진이_다른_장소에_배정되면_거부한다() {
        assertInvalidReferences(new Result("요약", List.of(block(1, 12L, 21L))), request());
    }

    @Test
    void 참조_검증_시_결과_형식도_검증한다() {
        assertInvalidReferences(new Result(" ", List.of(block(1, 11L, 21L))), request());
    }

    @Test
    void 요청이나_장소가_없으면_거부한다() {
        var result = new Result("요약", List.of(block(1, 11L, 21L)));
        assertInvalidReferences(result, null);
        var request = request();
        assertInvalidReferences(result, new StoryGenerationMessage(request.tripId(), request.executionId(),
                request.tripName(), request.period(), request.mood(), null));
    }

    private void assertInvalid(Result result) {
        assertThatThrownBy(() -> validator.validate(result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID.message());
    }

    private void assertInvalidReferences(Result result, StoryGenerationMessage request) {
        assertThatThrownBy(() -> validator.validate(result, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID.message());
    }

    private static Block block(Integer order, Long placeId, Long attachmentId) {
        return new Block(order, placeId, attachmentId, "첫날", "사진 설명", null);
    }

    private static StoryGenerationMessage request() {
        return new StoryGenerationMessage(241L, "execution-1", "여행",
                new StoryGenerationMessage.Period(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11)),
                Story.Mood.PLAIN, List.of(
                new StoryGenerationMessage.Place(11L, "장소11", List.of(photo(21L), photo(22L))),
                new StoryGenerationMessage.Place(12L, "장소12", List.of(photo(23L)))));
    }

    private static StoryGenerationMessage.Attachment photo(Long id) {
        return new StoryGenerationMessage.Attachment(id, "analysis/" + id + ".jpg",
                OffsetDateTime.parse("2026-10-10T09:00:00+09:00"), null);
    }
}
