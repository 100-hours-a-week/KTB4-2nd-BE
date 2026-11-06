package com.yeodam.yeodambe.story.event.handler;

import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.event.StoryCreateResultEvent;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.service.StoryGenerationCompletionService;
import com.yeodam.yeodambe.story.service.StoryGenerationTerminalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class StoryCreateResultEventHandlerTest {
    private final StoryGenerationJobRepository jobs = mock(StoryGenerationJobRepository.class);
    private final StoryGenerationCompletionService completion = mock(StoryGenerationCompletionService.class);
    private final StoryGenerationTerminalService terminal = mock(StoryGenerationTerminalService.class);
    private final StoryCreateResultEventHandler handler = new StoryCreateResultEventHandler(jobs, completion, terminal);
    private final StoryGenerationJob job = new StoryGenerationJob("execution-1", 241L, 2L, Story.Mood.PLAIN);

    @ParameterizedTest
    @EnumSource(value = StoryGenerationAiResponse.Status.class, names = {"COMPLETED", "FAILED", "CANCELED"})
    void 실행_ID로_생성_작업을_조회하고_종료_결과를_전달한다(StoryGenerationAiResponse.Status status) {
        prepareJob();
        var event = event("story_result", 241L, "execution-1", status);

        handler.handle(event);

        verify(jobs).findByExecutionId("execution-1");
        if (status == StoryGenerationAiResponse.Status.COMPLETED) {
            verify(completion).complete(10L, event.toResponse());
            verifyNoInteractions(terminal);
        } else {
            verify(terminal).apply(10L, event.toResponse());
            verifyNoInteractions(completion);
        }
    }

    @Test
    void 알_수_없는_실행은_결과_저장_없이_무시한다() {
        when(jobs.findByExecutionId("old-execution")).thenReturn(Optional.empty());

        handler.handle(event("story_result", 241L, "old-execution", StoryGenerationAiResponse.Status.COMPLETED));

        verifyNoInteractions(completion, terminal);
    }

    @ParameterizedTest
    @EnumSource(value = StoryGenerationJob.Status.class, names = {"COMPLETED", "FAILED", "CANCELED"})
    void 종료된_작업의_중복되거나_오래된_결과는_무시한다(StoryGenerationJob.Status status) {
        prepareJob();
        switch (status) {
            case COMPLETED -> job.complete(99L);
            case FAILED -> job.fail("WORKER_DEAD");
            case CANCELED -> job.cancel();
            default -> throw new AssertionError("Unexpected test status");
        }

        handler.handle(event("story_result", 241L, "execution-1", StoryGenerationAiResponse.Status.COMPLETED));

        verifyNoInteractions(completion, terminal);
    }

    @ParameterizedTest
    @MethodSource("invalidEvents")
    void 잘못된_이벤트나_종료_전_이벤트는_작업을_조회하지_않는다(StoryCreateResultEvent event) {
        assertThatThrownBy(() -> handler.handle(event)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jobs, completion, terminal);
    }

    static Stream<StoryCreateResultEvent> invalidEvents() {
        return Stream.of(event("process_result", 241L, "execution-1", StoryGenerationAiResponse.Status.COMPLETED),
                event(null, 241L, "execution-1", StoryGenerationAiResponse.Status.COMPLETED),
                event("story_result", null, "execution-1", StoryGenerationAiResponse.Status.COMPLETED),
                event("story_result", 0L, "execution-1", StoryGenerationAiResponse.Status.COMPLETED),
                event("story_result", 241L, null, StoryGenerationAiResponse.Status.COMPLETED),
                event("story_result", 241L, " ", StoryGenerationAiResponse.Status.COMPLETED),
                event("story_result", 241L, "execution-1", null),
                event("story_result", 241L, "execution-1", StoryGenerationAiResponse.Status.QUEUED),
                event("story_result", 241L, "execution-1", StoryGenerationAiResponse.Status.PROCESSING));
    }

    @Test
    void 실행_ID가_같아도_여행이_다르면_거부한다() {
        prepareJob();

        assertThatThrownBy(() -> handler.handle(event("story_result", 242L, "execution-1",
                StoryGenerationAiResponse.Status.COMPLETED))).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(completion, terminal);
    }

    @Test
    void 저장_실패를_전파한다() {
        prepareJob();
        var event = event("story_result", 241L, "execution-1", StoryGenerationAiResponse.Status.COMPLETED);
        var failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(completion).complete(10L, event.toResponse());

        assertThatThrownBy(() -> handler.handle(event)).isSameAs(failure);
        verifyNoInteractions(terminal);
    }

    @Test
    void null_페이로드를_거부한다() {
        assertThatThrownBy(() -> handler.handle(null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jobs, completion, terminal);
    }

    private void prepareJob() {
        ReflectionTestUtils.setField(job, "id", 10L);
        when(jobs.findByExecutionId("execution-1")).thenReturn(Optional.of(job));
    }

    private static StoryCreateResultEvent event(String type, Long tripId, String executionId,
                                               StoryGenerationAiResponse.Status status) {
        return new StoryCreateResultEvent(type, tripId, executionId, status, null, null);
    }
}
