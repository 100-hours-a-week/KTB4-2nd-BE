package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.service.response.StoryGenerationStatusResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class StoryGenerationStatusResponseTest {
    @ParameterizedTest
    @EnumSource(value = StoryGenerationJob.Status.class,
            mode = EnumSource.Mode.EXCLUDE, names = "COMPLETED")
    void 미완료이거나_성공하지_않은_응답은_준비된_ID를_숨긴다(StoryGenerationJob.Status status) {
        var error = new StoryGenerationStatusResponse.Failure("failure", "failed");
        var response = new StoryGenerationStatusResponse(1L, status, 2L, 10L, error);

        assertThat(response.storyId()).isNull();
        assertThat(response.generationId()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.statusVersion()).isEqualTo(2L);
        assertThat(response.error()).isEqualTo(error);
    }

    @Test
    void 완료된_스토리_ID를_공개한다() {
        var response = new StoryGenerationStatusResponse(
                1L, StoryGenerationJob.Status.COMPLETED, 3L, 10L, null);

        assertThat(response.storyId()).isEqualTo(10L);
    }
}
