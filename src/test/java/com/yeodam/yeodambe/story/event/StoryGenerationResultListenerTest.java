package com.yeodam.yeodambe.story.event;

import com.yeodam.yeodambe.integration.event.EventJsonCodec;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.event.handler.StoryCreateResultEventHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class StoryGenerationResultListenerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StoryCreateResultEventHandler handler = mock(StoryCreateResultEventHandler.class);
    private final StoryGenerationResultListener listener = new StoryGenerationResultListener(
            objectMapper, new EventJsonCodec(objectMapper), handler
    );

    private static final String STORY_RESULT = """
            {"type":"story_result","trip_id":77,"execution_id":"execution-1",
             "status":"CANCELED","result":null,"error":null}
            """;

    @Test
    void deserializesStoryResultAndDelegatesToHandler() {
        listener.receive(STORY_RESULT);

        var captor = ArgumentCaptor.forClass(StoryCreateResultEvent.class);
        verify(handler).handle(captor.capture());
        var event = captor.getValue();
        assertThat(event.tripId()).isEqualTo(77L);
        assertThat(event.executionId()).isEqualTo("execution-1");
        assertThat(event.status()).isEqualTo(StoryGenerationAiResponse.Status.CANCELED);
    }

    @Test
    void skipsPhotoProcessingResultOnSharedTopic() {
        listener.receive("""
                {"type":"process_result","trip_id":77,"status":"COMPLETED"}
                """);

        verifyNoInteractions(handler);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "null", "{}", "{\"type\":\"\"}", "{broken"})
    void rejectsUnreadableOrMissingMessageType(String message) {
        assertThatThrownBy(() -> listener.receive(message)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(handler);
    }

    @Test
    void propagatesSaveFailureToKafkaErrorHandler() {
        var failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(handler).handle(any(StoryCreateResultEvent.class));

        assertThatThrownBy(() -> listener.receive(STORY_RESULT)).isSameAs(failure);
    }
}
