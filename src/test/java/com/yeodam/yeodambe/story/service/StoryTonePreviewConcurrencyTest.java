package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryTonePreviewTimeoutException;
import com.yeodam.yeodambe.common.ratelimit.RequestRateLimitExceededException;
import com.yeodam.yeodambe.integration.service.RunpodStatusService;
import com.yeodam.yeodambe.integration.service.response.RunpodStatusResponse;
import com.yeodam.yeodambe.story.client.StoryTonePreviewAiClient;
import com.yeodam.yeodambe.story.client.request.StoryTonePreviewAiRequest;
import com.yeodam.yeodambe.story.client.response.StoryTonePreviewAiResponse;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.service.request.StoryTonePreviewRequest;
import com.yeodam.yeodambe.story.service.response.StoryTonePreviewResponse;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoryTonePreviewConcurrencyTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 두_실행_중에는_AI를_추가_호출하지_않고_성공과_시간초과_후_슬롯을_반환한다(boolean fail) throws Exception {
        StoryTonePreviewInputService input = mock(StoryTonePreviewInputService.class);
        RunpodStatusService ready = mock(RunpodStatusService.class);
        StoryTonePreviewAiClient ai = mock(StoryTonePreviewAiClient.class);
        StoryTonePreviewService service = new StoryTonePreviewService(
                input, ready, ai, mock(StoryTonePreviewResultValidator.class), mock(TripAttachmentStorageClient.class), 2);
        StoryTonePreviewRequest request = new StoryTonePreviewRequest(Story.Mood.PLAIN, List.of());
        StoryTonePreviewAiRequest aiRequest = new StoryTonePreviewAiRequest("여행", null, Story.Mood.PLAIN, List.of());
        when(input.prepare(2L, 9L, request)).thenReturn(
                new StoryTonePreviewInputService.PreparedRequest(7L, aiRequest, List.of()));
        when(ready.getStatus()).thenReturn(new RunpodStatusResponse(true, true));
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(ai.preview(7L, aiRequest)).thenAnswer(invocation -> {
            if (calls.incrementAndGet() <= 2) {
                started.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                if (fail) {
                    throw new StoryTonePreviewTimeoutException();
                }
            }
            return new StoryTonePreviewAiResponse("요약", List.of());
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                Future<StoryTonePreviewResponse> first = executor.submit(() -> service.preview(2L, 9L, request));
                Future<StoryTonePreviewResponse> second = executor.submit(() -> service.preview(2L, 9L, request));
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.preview(2L, 9L, request))
                        .isInstanceOf(RequestRateLimitExceededException.class);
                verify(ai, times(2)).preview(7L, aiRequest);
                release.countDown();
                for (Future<StoryTonePreviewResponse> running : List.of(first, second)) {
                    if (fail) {
                        assertThatThrownBy(() -> running.get(5, TimeUnit.SECONDS))
                                .hasCauseInstanceOf(StoryTonePreviewTimeoutException.class);
                    } else {
                        assertThat(running.get(5, TimeUnit.SECONDS).storySummary()).isEqualTo("요약");
                    }
                }
                assertThat(service.preview(2L, 9L, request).storySummary()).isEqualTo("요약");
            } finally {
                release.countDown();
            }
        }
    }
}
