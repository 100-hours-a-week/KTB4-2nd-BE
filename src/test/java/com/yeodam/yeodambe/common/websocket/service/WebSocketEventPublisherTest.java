package com.yeodam.yeodambe.common.websocket.service;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.websocket.config.WebSocketEventProperties;
import com.yeodam.yeodambe.common.websocket.exception.WebSocketEventInternalErrorMessage;
import com.yeodam.yeodambe.common.websocket.service.request.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSocketEventPublisherTest {

    private static final String CHANNEL = "yeodam:test:websocket:events:v1";
    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant TIME = Instant.parse("2026-10-28T00:00:00Z");
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final WebSocketEventPublisher publisher =
            new WebSocketEventPublisher(redis, mapper, new WebSocketEventProperties(CHANNEL));

    @ParameterizedTest
    @MethodSource("validTargets")
    void serializesValidEventsAndPublishesToTheConfiguredChannel(
            WebSocketEventType type, WebSocketEventTargetType targetType
    ) {
        var event = new WebSocketEvent(ID, type, 1, TIME,
                new WebSocketEventTarget(targetType, 42L), new ApiResponse<>("TEST_EVENT", Map.of("status", "COMPLETED")));
        var json = org.mockito.ArgumentCaptor.forClass(String.class);

        publisher.publish(event);

        verify(redis).convertAndSend(eq(CHANNEL), json.capture());
        assertThat(mapper.readValue(json.getValue(), WebSocketEvent.class)).isEqualTo(event);
    }

    static Stream<Arguments> validTargets() {
        return Stream.of(
                Arguments.of(WebSocketEventType.AI_TASK, WebSocketEventTargetType.USER),
                Arguments.of(WebSocketEventType.COMMAND_RESULT, WebSocketEventTargetType.USER),
                Arguments.of(WebSocketEventType.TRIP_EVENT, WebSocketEventTargetType.TRIP)
        );
    }

    @ParameterizedTest
    @MethodSource("invalidEvents")
    void rejectsInvalidInputBeforeCallingRedis(WebSocketEvent event, WebSocketEventInternalErrorMessage error) {
        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(error.message());
        verifyNoInteractions(redis);
    }

    static Stream<Arguments> invalidEvents() {
        var user = new WebSocketEventTarget(WebSocketEventTargetType.USER, 42L);
        var body = new ApiResponse<>("TEST_EVENT", "data");
        return Stream.of(
                Arguments.of(null, WebSocketEventInternalErrorMessage.EVENT_MISSING),
                Arguments.of(new WebSocketEvent(null, WebSocketEventType.AI_TASK, 1, TIME, user, body), WebSocketEventInternalErrorMessage.EVENT_ID_MISSING),
                Arguments.of(new WebSocketEvent(ID, null, 1, TIME, user, body), WebSocketEventInternalErrorMessage.EVENT_TYPE_MISSING),
                Arguments.of(new WebSocketEvent(ID, WebSocketEventType.AI_TASK, 2, TIME, user, body), WebSocketEventInternalErrorMessage.SCHEMA_VERSION_UNSUPPORTED),
                Arguments.of(new WebSocketEvent(ID, WebSocketEventType.AI_TASK, 1, null, user, body), WebSocketEventInternalErrorMessage.OCCURRED_AT_MISSING),
                Arguments.of(new WebSocketEvent(ID, WebSocketEventType.AI_TASK, 1, TIME, null, body), WebSocketEventInternalErrorMessage.TARGET_MISSING),
                Arguments.of(event(new WebSocketEventTarget(null, 42L), body), WebSocketEventInternalErrorMessage.TARGET_TYPE_MISSING),
                Arguments.of(event(new WebSocketEventTarget(WebSocketEventTargetType.USER, null), body), WebSocketEventInternalErrorMessage.TARGET_ID_INVALID),
                Arguments.of(event(new WebSocketEventTarget(WebSocketEventTargetType.USER, 0L), body), WebSocketEventInternalErrorMessage.TARGET_ID_INVALID),
                Arguments.of(event(new WebSocketEventTarget(WebSocketEventTargetType.USER, -1L), body), WebSocketEventInternalErrorMessage.TARGET_ID_INVALID),
                Arguments.of(event(user, null), WebSocketEventInternalErrorMessage.PAYLOAD_MISSING),
                Arguments.of(event(user, new ApiResponse<>((String) null, null)), WebSocketEventInternalErrorMessage.PAYLOAD_MESSAGE_MISSING),
                Arguments.of(event(user, new ApiResponse<>("  ", null)), WebSocketEventInternalErrorMessage.PAYLOAD_MESSAGE_MISSING),
                Arguments.of(event(new WebSocketEventTarget(WebSocketEventTargetType.TRIP, 42L), body), WebSocketEventInternalErrorMessage.TARGET_TYPE_MISMATCH),
                Arguments.of(new WebSocketEvent(ID, WebSocketEventType.COMMAND_RESULT, 1, TIME,
                        new WebSocketEventTarget(WebSocketEventTargetType.TRIP, 42L), body), WebSocketEventInternalErrorMessage.TARGET_TYPE_MISMATCH),
                Arguments.of(new WebSocketEvent(ID, WebSocketEventType.TRIP_EVENT, 1, TIME, user, body), WebSocketEventInternalErrorMessage.TARGET_TYPE_MISMATCH)
        );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void rejectsMissingChannel(String channel) {
        assertThatThrownBy(() -> new WebSocketEventPublisher(redis, mapper, new WebSocketEventProperties(channel)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(WebSocketEventInternalErrorMessage.CHANNEL_NOT_CONFIGURED.message());
    }

    @Test
    void freezesValidatedJsonWithoutPublishingAndReusesTheExactBytes() {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("status", "BEFORE");
        String frozenJson = publisher.serialize(withData(data));
        verifyNoInteractions(redis);
        data.put("status", "AFTER");

        publisher.publishSerialized(frozenJson);

        verify(redis).convertAndSend(CHANNEL, frozenJson);
        assertThat(frozenJson).contains("BEFORE").doesNotContain("AFTER");
    }

    @Test
    void serializationAloneEnforcesTheEventContractAndSize() {
        assertThatThrownBy(() -> publisher.serialize(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher.serialize(withData("가".repeat(22_000))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(WebSocketEventInternalErrorMessage.EVENT_TOO_LARGE.message());
        verifyNoInteractions(redis);
    }

    @Test
    void serializedPublicationRejectsInvalidSchemaAndOversizedJson() {
        String validJson = publisher.serialize(withData("data"));
        assertThatThrownBy(() -> publisher.publishSerialized("not-json"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> publisher.publishSerialized(validJson.replace("\"schemaVersion\":1", "\"schemaVersion\":2")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher.publishSerialized(mapper.writeValueAsString(withData("가".repeat(22_000)))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(redis);
    }

    @Test
    void acceptsExactlySixtyFourKiB() {
        int overhead = mapper.writeValueAsString(withData("")).getBytes(StandardCharsets.UTF_8).length;
        var boundary = withData("a".repeat(65_536 - overhead));
        assertThat(mapper.writeValueAsString(boundary).getBytes(StandardCharsets.UTF_8)).hasSize(65_536);

        publisher.publish(boundary);

        verify(redis).convertAndSend(eq(CHANNEL), anyString());
    }

    @Test
    void rejectsOneByteAboveTheLimit() {
        int overhead = mapper.writeValueAsString(withData("")).getBytes(StandardCharsets.UTF_8).length;
        assertTooLarge(withData("a".repeat(65_537 - overhead)));
    }

    @Test
    void countsUtf8BytesRatherThanCharacters() {
        var event = withData("가".repeat(22_000));
        assertThat(mapper.writeValueAsString(event).length()).isLessThan(65_536);
        assertTooLarge(event);
    }

    @Test
    void propagatesRedisFailure() {
        var failure = new RedisConnectionFailureException("test redis unavailable");
        doThrow(failure).when(redis).convertAndSend(eq(CHANNEL), anyString());

        assertThatThrownBy(() -> publisher.publish(withData("data"))).isSameAs(failure);
    }

    @Test
    void doesNotClaimDeliveryWhenThereAreNoSubscribers() {
        when(redis.convertAndSend(eq(CHANNEL), anyString())).thenReturn(0L);

        assertThatCode(() -> publisher.publish(withData("data"))).doesNotThrowAnyException();
        verify(redis).convertAndSend(eq(CHANNEL), anyString());
    }

    @Test
    void propagatesSerializationFailureWithoutPublishing() {
        assertThatThrownBy(() -> publisher.publish(withData(new ExplodingPayload())))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(redis);
    }

    private void assertTooLarge(WebSocketEvent event) {
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(WebSocketEventInternalErrorMessage.EVENT_TOO_LARGE.message());
        verifyNoInteractions(redis);
    }

    private static WebSocketEvent event(WebSocketEventTarget target, ApiResponse<?> payload) {
        return new WebSocketEvent(ID, WebSocketEventType.AI_TASK, 1, TIME, target, payload);
    }

    private static WebSocketEvent withData(Object data) {
        return event(new WebSocketEventTarget(WebSocketEventTargetType.USER, 42L),
                new ApiResponse<>("TEST_EVENT", data));
    }

    public static class ExplodingPayload {
        public String getValue() {
            throw new IllegalStateException("test serialization failure");
        }
    }
}
