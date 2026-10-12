package com.yeodam.yeodambe.story.client;

import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.integration.config.KafkaTopicProperties;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StoryGenerationPublisherTest {
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final KafkaTopicProperties topics = new KafkaTopicProperties(
            Arrays.stream(EventType.values()).collect(Collectors.toMap(type -> type,
                    type -> type == EventType.STORY_CREATE_REQUEST ? "ai-jobs" : "unused." + type.name())));
    private final StoryGenerationPublisher publisher = new StoryGenerationPublisher(kafkaTemplate, topics);
    private final String payload = "{\"type\":\"story\",\"execution_id\":\"saved-execution\"}";

    @Test
    void sendsStoredJsonToConfiguredTopicAndReturnsPendingFuture() {
        var pending = new CompletableFuture<SendResult<String, String>>();
        when(kafkaTemplate.send("ai-jobs", payload)).thenReturn(pending);

        var result = publisher.publish(payload);

        assertThat(result).isSameAs(pending);
        assertThat(result.isDone()).isFalse();
        verify(kafkaTemplate).send("ai-jobs", payload);
        verifyNoMoreInteractions(kafkaTemplate);
    }

    @Test
    void brokerFailureRemainsVisibleToCallerWithoutApplicationRepublish() {
        var pending = new CompletableFuture<SendResult<String, String>>();
        when(kafkaTemplate.send("ai-jobs", payload)).thenReturn(pending);
        var result = publisher.publish(payload);
        var failure = new IllegalStateException("broker unavailable");

        pending.completeExceptionally(failure);

        assertThatThrownBy(result::join).isInstanceOf(CompletionException.class).hasCause(failure);
        verify(kafkaTemplate).send("ai-jobs", payload);
        verifyNoMoreInteractions(kafkaTemplate);
    }

    @Test
    void successfulCompletionRemainsVisibleToCaller() {
        var pending = new CompletableFuture<SendResult<String, String>>();
        when(kafkaTemplate.send("ai-jobs", payload)).thenReturn(pending);
        var result = publisher.publish(payload);
        @SuppressWarnings("unchecked")
        SendResult<String, String> acknowledged = mock(SendResult.class);

        pending.complete(acknowledged);

        assertThat(result.join()).isSameAs(acknowledged);
    }
}
