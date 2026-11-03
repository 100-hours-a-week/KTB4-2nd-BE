package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryTonePreviewFailedException;
import com.yeodam.yeodambe.story.client.request.StoryTonePreviewAiRequest;
import com.yeodam.yeodambe.story.client.response.StoryTonePreviewAiResponse;
import com.yeodam.yeodambe.story.entity.Story;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

class StoryTonePreviewResultValidatorTest {
    private final StoryTonePreviewResultValidator validator = new StoryTonePreviewResultValidator();

    @Test
    void 정상결과와_생성된라벨과_null메모와_30개블록을_허용한다() {
        for (int count : List.of(1, 30)) {
            var sent = IntStream.rangeClosed(1, count)
                    .mapToObj(i -> sent(i, (long) i, null, null)).toList();
            var received = IntStream.rangeClosed(1, count)
                    .mapToObj(i -> received(i, (long) i, "생성 라벨", "캡션", null)).toList();
            assertThatCode(() -> validator.validate(request(sent),
                    new StoryTonePreviewAiResponse("요약", received))).doesNotThrowAnyException();
        }
    }

    @Test
    void 빈응답과_목록누락과_개수불일치와_null블록을_거절한다() {
        var request = request(List.of(sent(1, 101L, null, null)));
        for (var response : Arrays.asList(null,
                new StoryTonePreviewAiResponse("요약", null),
                new StoryTonePreviewAiResponse("요약", List.of()),
                new StoryTonePreviewAiResponse("요약", Collections.singletonList(null)))) {
            rejects(request, response);
        }
    }

    @Test
    void 사진ID와_순서누락및_불일치와_사진순서뒤바뀜을_거절한다() {
        var request = request(List.of(sent(1, 101L, null, null)));
        for (var block : List.of(received(null, 101L, "날", "글", null),
                received(2, 101L, "날", "글", null), received(1, null, "날", "글", null),
                received(1, 999L, "날", "글", null))) {
            rejects(request, new StoryTonePreviewAiResponse("요약", List.of(block)));
        }
        rejects(request(List.of(sent(1, 101L, null, null), sent(2, 102L, null, null))),
                new StoryTonePreviewAiResponse("요약", List.of(
                        received(1, 102L, "날", "글", null), received(2, 101L, "날", "글", null))));
    }

    @Test
    void 기존라벨변경과_null메모채우기를_거절한다() {
        var request = request(List.of(sent(1, 101L, "기존 라벨", null)));
        rejects(request, new StoryTonePreviewAiResponse("요약",
                List.of(received(1, 101L, "다른 라벨", "캡션", null))));
        rejects(request, new StoryTonePreviewAiResponse("요약",
                List.of(received(1, 101L, "기존 라벨", "캡션", "추가된 메모"))));
    }

    @Test
    void 요약20자_라벨캡션40자_메모125자는_코드포인트로_검사한다() {
        String label = "😀".repeat(40);
        var request = request(List.of(sent(1, 101L, label, "기존 메모")));
        var block = received(1, 101L, label, label, "😀".repeat(125));
        assertThatCode(() -> validator.validate(request,
                new StoryTonePreviewAiResponse("😀".repeat(20), List.of(block))))
                .doesNotThrowAnyException();
        rejects(request, new StoryTonePreviewAiResponse("😀".repeat(21), List.of(block)));
        rejects(request, new StoryTonePreviewAiResponse("요약", List.of(
                received(1, 101L, label, label + "가", null))));
        rejects(request, new StoryTonePreviewAiResponse("요약", List.of(
                received(1, 101L, label, label, "😀".repeat(126)))));
        rejects(request(List.of(sent(1, 101L, null, null))),
                new StoryTonePreviewAiResponse("요약", List.of(
                        received(1, 101L, label + "가", "캡션", null))));
    }

    @Test
    void 필수문구의_null과_보이지않는문자를_거절한다() {
        var request = request(List.of(sent(1, 101L, null, null)));
        for (String text : Arrays.asList(null, "", " ", "\u200B")) {
            rejects(request, new StoryTonePreviewAiResponse(text,
                    List.of(received(1, 101L, "날", "글", null))));
            rejects(request, new StoryTonePreviewAiResponse("요약",
                    List.of(received(1, 101L, text, "글", null))));
            rejects(request, new StoryTonePreviewAiResponse("요약",
                    List.of(received(1, 101L, "날", text, null))));
        }
    }

    @Test
    void AI_JSON필드명을_응답객체로_읽는다() {
        var response = new ObjectMapper().readValue("""
                {"story_summary":"요약","blocks":[{"order_number":1,
                "trip_attachment_id":101,"day_label":"날","detail_summary":"글","memo":null}]}
                """, StoryTonePreviewAiResponse.class);
        assertThat(response.storySummary()).isEqualTo("요약");
        assertThatCode(() -> validator.validate(
                request(List.of(sent(1, 101L, null, null))), response)).doesNotThrowAnyException();
    }

    private void rejects(StoryTonePreviewAiRequest request, StoryTonePreviewAiResponse response) {
        assertThatThrownBy(() -> validator.validate(request, response))
                .isInstanceOf(StoryTonePreviewFailedException.class);
    }

    private StoryTonePreviewAiRequest request(List<StoryTonePreviewAiRequest.Block> blocks) {
        return new StoryTonePreviewAiRequest("여행", null, Story.Mood.PLAIN, blocks);
    }

    private StoryTonePreviewAiRequest.Block sent(int order, Long id, String label, String memo) {
        return new StoryTonePreviewAiRequest.Block(order, id, "장소", null, "analyze/key", label, null, memo);
    }

    private StoryTonePreviewAiResponse.Block received(Integer order, Long id, String label, String caption, String memo) {
        return new StoryTonePreviewAiResponse.Block(order, id, label, caption, memo);
    }
}
