package com.yeodam.yeodambe.common.websocket.service;

import com.yeodam.yeodambe.common.websocket.config.WebSocketEventProperties;
import com.yeodam.yeodambe.common.websocket.exception.WebSocketEventInternalErrorMessage;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEvent;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTargetType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

@Service
public class WebSocketEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WebSocketEventPublisher.class);
    private static final int MAX_EVENT_BYTES = 64 * 1024;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final WebSocketEventProperties properties;

    public WebSocketEventPublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            WebSocketEventProperties properties
    ) {
        Assert.hasText(
                properties.channel(),
                WebSocketEventInternalErrorMessage.CHANNEL_NOT_CONFIGURED.message()
        );

        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void publish(WebSocketEvent event) {
        send(serialize(event));
    }

    public String serialize(WebSocketEvent event) {
        validate(event);

        String json = objectMapper.writeValueAsString(event);

        validateSize(json);
        return json;
    }

    // 저장된 스키마를 검사해도 재직렬화하지 않아 동결한 JSON을 그대로 발행한다.
    public void publishSerialized(String json) {
        Assert.hasText(json, WebSocketEventInternalErrorMessage.EVENT_MISSING.message());
        validateSize(json);
        validate(objectMapper.readValue(json, WebSocketEvent.class));
        send(json);
    }

    private void validateSize(String json) {
        Assert.isTrue(
                json.getBytes(StandardCharsets.UTF_8).length <= MAX_EVENT_BYTES,
                WebSocketEventInternalErrorMessage.EVENT_TOO_LARGE.message()
        );
    }

    private void send(String json) {
        try {
            redisTemplate.convertAndSend(properties.channel(), json);
        } catch (RuntimeException exception) {
            log.atWarn().addKeyValue("event", "websocket_event_publish")
                    .addKeyValue("result", "failure")
                    .addKeyValue("failure", exception.getClass().getName())
                    .log("WebSocket event Redis publication failed.");
            throw exception;
        }
    }

    private void validate(WebSocketEvent event) {
        Assert.notNull(event,
                WebSocketEventInternalErrorMessage.EVENT_MISSING.message());
        Assert.notNull(event.eventId(),
                WebSocketEventInternalErrorMessage.EVENT_ID_MISSING.message());
        Assert.notNull(event.type(),
                WebSocketEventInternalErrorMessage.EVENT_TYPE_MISSING.message());
        Assert.isTrue(event.schemaVersion() == 1,
                WebSocketEventInternalErrorMessage.SCHEMA_VERSION_UNSUPPORTED.message());
        Assert.notNull(event.occurredAt(),
                WebSocketEventInternalErrorMessage.OCCURRED_AT_MISSING.message());
        Assert.notNull(event.target(),
                WebSocketEventInternalErrorMessage.TARGET_MISSING.message());
        Assert.notNull(event.target().type(),
                WebSocketEventInternalErrorMessage.TARGET_TYPE_MISSING.message());
        Assert.isTrue(event.target().id() != null && event.target().id() > 0,
                WebSocketEventInternalErrorMessage.TARGET_ID_INVALID.message());
        Assert.notNull(event.payload(),
                WebSocketEventInternalErrorMessage.PAYLOAD_MISSING.message());
        Assert.hasText(event.payload().message(),
                WebSocketEventInternalErrorMessage.PAYLOAD_MESSAGE_MISSING.message());

        WebSocketEventTargetType expectedTarget = switch (event.type()) {
            case AI_TASK, COMMAND_RESULT -> WebSocketEventTargetType.USER;
            case TRIP_EVENT -> WebSocketEventTargetType.TRIP;
        };

        Assert.isTrue(event.target().type() == expectedTarget,
                WebSocketEventInternalErrorMessage.TARGET_TYPE_MISMATCH.message());
    }
}
