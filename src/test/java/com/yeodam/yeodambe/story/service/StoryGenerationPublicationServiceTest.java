package com.yeodam.yeodambe.story.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yeodam.yeodambe.story.client.StoryGenerationPublisher;
import com.yeodam.yeodambe.story.entity.StoryGenerationOutbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.kafka.support.SendResult;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StoryGenerationPublicationServiceTest {
    private final StoryGenerationPublicationStateService state = mock(StoryGenerationPublicationStateService.class);
    private final StoryGenerationPublisher publisher = mock(StoryGenerationPublisher.class);
    private final StoryGenerationOutbox outbox = mock(StoryGenerationOutbox.class);
    private final StoryGenerationPublicationService service = new StoryGenerationPublicationService(state, publisher);
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void prepare() {
        logger = (Logger) LoggerFactory.getLogger(StoryGenerationPublicationService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        when(state.begin(7L)).thenReturn(outbox);
        when(outbox.getId()).thenReturn(11L);
        when(outbox.getPayload()).thenReturn("stored-json");
        when(outbox.getRequestId()).thenReturn("request-original");
        when(outbox.getGenerationId()).thenReturn(7L);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void recordsSuccessOnlyAfterKafkaFutureCompletes() {
        var sending = new CompletableFuture<SendResult<String, String>>();
        when(publisher.publish("stored-json")).thenReturn(sending);

        var result = service.publish(7L);

        assertThat(result.isDone()).isFalse();
        verify(state, never()).succeeded(anyLong(), any());
        sending.complete(null);
        assertThat(result.join()).isNull();
        InOrder order = inOrder(state, publisher);
        order.verify(state).begin(7L);
        order.verify(publisher).publish("stored-json");
        order.verify(state).succeeded(eq(11L), any(LocalDateTime.class));
        verify(state, never()).failed(anyLong(), anyString());
        assertPublicationLog("story_generation_published", "success", null);
    }

    @Test
    void recordsAsynchronousFailureAndPreservesItsCause() {
        var sending = new CompletableFuture<SendResult<String, String>>();
        when(publisher.publish("stored-json")).thenReturn(sending);
        var result = service.publish(7L);
        var failure = new IllegalStateException("broker unavailable");

        sending.completeExceptionally(failure);

        assertThatThrownBy(result::join).isInstanceOf(CompletionException.class).hasCause(failure);
        verify(state).failed(11L, failure.toString());
        verify(state, never()).succeeded(anyLong(), any());
        verify(publisher).publish("stored-json");
        verifyNoMoreInteractions(publisher);
        assertPublicationLog("story_generation_publish_failed", "failed", failure);
    }

    @Test
    void recordsImmediateSendFailureAndReturnsFailedFuture() {
        var failure = new IllegalStateException("send setup failed");
        when(publisher.publish("stored-json")).thenThrow(failure);

        var result = service.publish(7L);

        assertThatThrownBy(result::join).isInstanceOf(CompletionException.class).hasCause(failure);
        verify(state).failed(11L, failure.toString());
        verify(state, never()).succeeded(anyLong(), any());
        assertPublicationLog("story_generation_publish_failed", "failed", failure);
    }

    @Test
    void databaseSuccessRecordFailureDoesNotBecomeSuccessfulCompletion() {
        var sending = new CompletableFuture<SendResult<String, String>>();
        when(publisher.publish("stored-json")).thenReturn(sending);
        var databaseFailure = new IllegalStateException("database unavailable");
        doThrow(databaseFailure).when(state).succeeded(eq(11L), any());
        var result = service.publish(7L);

        sending.complete(null);

        assertThatThrownBy(result::join).isInstanceOf(CompletionException.class).hasCause(databaseFailure);
        verify(state, never()).failed(anyLong(), anyString());
    }

    @Test
    void missingOutboxStopsBeforeKafkaSend() {
        var failure = new NoSuchElementException();
        when(state.begin(7L)).thenThrow(failure);

        assertThatThrownBy(() -> service.publish(7L)).isSameAs(failure);
        verifyNoInteractions(publisher);
    }

    private void assertPublicationLog(String eventName, String result, Throwable failure) {
        assertThat(appender.list).singleElement().satisfies(event -> {
            var fields = event.getKeyValuePairs().stream()
                    .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
            assertThat(fields).containsEntry("event", eventName)
                    .containsEntry("result", result)
                    .containsEntry("request_id", "request-original")
                    .containsEntry("job_id", 7L);
            if (failure != null) {
                assertThat(fields).containsEntry("failure_stage", "kafka_send");
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getMessage()).isEqualTo(failure.getMessage());
            } else {
                assertThat(event.getThrowableProxy()).isNull();
            }
        });
    }
}
