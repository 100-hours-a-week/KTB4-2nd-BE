package com.yeodam.websocket.common.websocket.service;

import com.yeodam.websocket.common.websocket.config.WebSocketEventProperties;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.connection.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WebSocketEventSubscriberTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private static final String CHANNEL = "yeodam:test:websocket:events:v1";
    private final RedisSubscriptionState subscriptionState = new RedisSubscriptionState();
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final WebSocketSessionManager sessionManager = new WebSocketSessionManager(scheduler, subscriptionState);
    private final WebSocketEventSubscriber subscriber = new WebSocketEventSubscriber(
            mapper, template, new WebSocketEventProperties(CHANNEL), subscriptionState, sessionManager
    );

    @Test
    void tracksSubscriptionAcknowledgementsForTheConfiguredChannel() {
        byte[] channel = CHANNEL.getBytes(StandardCharsets.UTF_8);
        assertThat(subscriptionState.isReady()).isFalse();
        subscriber.onChannelSubscribed(channel, 1);
        assertThat(subscriptionState.isReady()).isTrue();
        subscriber.onChannelUnsubscribed(channel, 0);
        assertThat(subscriptionState.isReady()).isFalse();
        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
        subscriber.onChannelUnsubscribed(channel, 0);
        verifyNoMoreInteractions(scheduler);
        subscriber.onChannelSubscribed(channel, 1);
        assertThat(subscriptionState.isReady()).isTrue();
        verifyNoInteractions(template);
    }

    @Test
    void doesNotRepeatClosureIfAnotherFailurePathAlreadyMarkedUnavailable() {
        byte[] channel = CHANNEL.getBytes(StandardCharsets.UTF_8);
        subscriber.onChannelSubscribed(channel, 1);
        subscriptionState.markUnavailable();

        subscriber.onChannelUnsubscribed(channel, 0);

        assertThat(subscriptionState.isReady()).isFalse();
        verifyNoInteractions(scheduler);
    }

    @Test
    void requestsClosureAgainIfSubscriptionIsLostAfterRecovery() {
        byte[] channel = CHANNEL.getBytes(StandardCharsets.UTF_8);
        subscriber.onChannelSubscribed(channel, 1);
        subscriber.onChannelUnsubscribed(channel, 0);
        subscriber.onChannelSubscribed(channel, 1);
        subscriber.onChannelUnsubscribed(channel, 0);

        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
        assertThat(subscriptionState.isReady()).isFalse();
    }

    @Test
    void ignoresAcknowledgementsForOtherChannels() {
        byte[] other = "other-channel".getBytes(StandardCharsets.UTF_8);
        subscriber.onChannelSubscribed(other, 1);
        assertThat(subscriptionState.isReady()).isFalse();
        subscriber.onChannelSubscribed(CHANNEL.getBytes(StandardCharsets.UTF_8), 1);
        subscriber.onChannelUnsubscribed(other, 0);
        assertThat(subscriptionState.isReady()).isTrue();
        verifyNoInteractions(template);
        verifyNoInteractions(scheduler);
    }

    @ParameterizedTest
    @CsvSource({"AI_TASK,USER,/queue/ai-tasks", "COMMAND_RESULT,USER,/queue/command-results",
            "TRIP_EVENT,TRIP,/topic/trips/42/events"})
    void routesOnlyThePayload(String type, String target, String destination) {
        String json = event(type, target);
        subscriber.onMessage(message(json), null);
        var payload = mapper.readTree(json).path("payload");
        if (target.equals("USER")) verify(template).convertAndSendToUser("42", destination, payload);
        else verify(template).convertAndSend(destination, payload);
        verifyNoMoreInteractions(template);
    }

    @ParameterizedTest
    @MethodSource("invalidMessages")
    void discardsInvalidMessageAndStillProcessesTheNext(String invalid) {
        assertThatCode(() -> subscriber.onMessage(message(invalid), null)).doesNotThrowAnyException();
        verifyNoInteractions(template);
        String valid = event("AI_TASK", "USER");
        subscriber.onMessage(message(valid), null);
        verify(template).convertAndSendToUser("42", "/queue/ai-tasks", mapper.readTree(valid).path("payload"));
    }

    static Stream<String> invalidMessages() {
        String valid = event("AI_TASK", "USER");
        return Stream.of("{broken", "null", valid.replace("AI_TASK", "UNKNOWN"),
                valid.replace("\"eventId\":\"11111111-1111-1111-1111-111111111111\"", "\"eventId\":null"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                valid.replace("\"type\":\"AI_TASK\"", "\"type\":null"),
                valid.replace("\"occurredAt\":\"2026-10-28T00:00:00Z\"", "\"occurredAt\":null"),
                valid.replace("{\"type\":\"USER\",\"id\":42}", "null"),
                valid.replace("\"type\":\"USER\"", "\"type\":null"),
                valid.replace("\"id\":42", "\"id\":0"),
                valid.replace("USER", "TRIP"),
                valid.replace("{\"message\":\"TEST\",\"data\":null}", "null"),
                valid.replace("{\"message\":\"TEST\",\"data\":null}", "[]"),
                valid.replace("\"message\":\"TEST\"", "\"message\":123"),
                valid.replace("\"message\":\"TEST\"", "\"message\":\" \""),
                valid.replace(",\"data\":null", ""), "a".repeat(65_537));
    }

    @Test
    void continuesAfterDispatchFailure() {
        String json = event("AI_TASK", "USER");
        var payload = mapper.readTree(json).path("payload");
        doThrow(new IllegalStateException("test send failure")).doNothing()
                .when(template).convertAndSendToUser("42", "/queue/ai-tasks", payload);
        assertThatCode(() -> subscriber.onMessage(message(json), null)).doesNotThrowAnyException();
        subscriber.onMessage(message(json), null);
        verify(template, times(2)).convertAndSendToUser("42", "/queue/ai-tasks", payload);
    }

    private static String event(String type, String target) {
        return """
                {"eventId":"11111111-1111-1111-1111-111111111111","type":"%s","schemaVersion":1,
                "occurredAt":"2026-10-28T00:00:00Z","target":{"type":"%s","id":42},
                "payload":{"message":"TEST","data":null}}
                """.formatted(type, target);
    }

    private Message message(String json) {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
