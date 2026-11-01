package com.yeodam.yeodambe.story.client;

import com.yeodam.yeodambe.story.client.request.StorySimilarAttachmentAiRequest;
import com.yeodam.yeodambe.story.client.response.StorySimilarAttachmentAiResponse;
import com.yeodam.yeodambe.story.service.response.StorySimilarAttachmentListResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StorySimilarAttachmentJsonTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void AI_요청은_계약의_snake_case_필드로_전송한다() {
        var request = new StorySimilarAttachmentAiRequest(501L, List.of(506L, 507L), 10);

        var actual = mapper.readTree(mapper.writeValueAsString(request));

        assertThat(actual).isEqualTo(mapper.readTree("""
                {"trip_attachment_id":501,"candidate_attachment_ids":[506,507],"limit":10}
                """));
    }

    @Test
    void AI_응답에서_사진_ID와_점수와_후보_순서를_읽는다() {
        var actual = mapper.readValue("""
                {"source_attachment_id":501,"items":[
                  {"trip_attachment_id":508,"score":0.94},
                  {"trip_attachment_id":506,"score":0.91}]}
                """, StorySimilarAttachmentAiResponse.class);

        assertThat(actual.sourceAttachmentId()).isEqualTo(501L);
        assertThat(actual.items()).containsExactly(
                new StorySimilarAttachmentAiResponse.Item(508L, 0.94),
                new StorySimilarAttachmentAiResponse.Item(506L, 0.91));
    }

    @Test
    void AI가_점수를_누락하면_0점과_구분한다() {
        var actual = mapper.readValue("""
                {"source_attachment_id":501,"items":[{"trip_attachment_id":506}]}
                """, StorySimilarAttachmentAiResponse.class);

        assertThat(actual.items().getFirst().score()).isNull();
    }

    @Test
    void 프론트_응답은_공개_명세의_camelCase_필드를_사용한다() {
        var response = new StorySimilarAttachmentListResponse(501L,
                List.of(new StorySimilarAttachmentListResponse.Item(506L, 0.94)));

        var actual = mapper.readTree(mapper.writeValueAsString(response));

        assertThat(actual).isEqualTo(mapper.readTree("""
                {"sourceTripAttachmentId":501,"items":[
                  {"tripAttachmentId":506,"similarityScore":0.94}]}
                """));
    }
}
