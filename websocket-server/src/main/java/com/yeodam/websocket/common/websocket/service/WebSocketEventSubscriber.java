package com.yeodam.websocket.common.websocket.service;

import com.yeodam.websocket.common.websocket.exception.WebSocketEventFailure;
import com.yeodam.websocket.common.websocket.service.request.WebSocketEvent;
import com.yeodam.websocket.common.websocket.service.request.WebSocketEventTargetType;
import com.yeodam.websocket.common.websocket.config.WebSocketEventProperties;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import org.springframework.data.redis.connection.SubscriptionListener;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class WebSocketEventSubscriber
        implements MessageListener, SubscriptionListener {

    private static final Logger log = LoggerFactory.getLogger(WebSocketEventSubscriber.class);
    private static final int MAX_EVENT_BYTES = 64 * 1024;

    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketEventProperties properties;
    private final RedisSubscriptionState subscriptionState;
    private final WebSocketSessionManager sessionManager;

    public WebSocketEventSubscriber(
            ObjectMapper objectMapper,
            SimpMessagingTemplate messagingTemplate,
            WebSocketEventProperties properties,
            RedisSubscriptionState subscriptionState,
            WebSocketSessionManager sessionManager
    ) {
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
        this.properties = properties;
        this.subscriptionState = subscriptionState;
        this.sessionManager = sessionManager;
    }

    @Override
    public void onChannelSubscribed(byte[] channel, long count) {
        if (properties.channel().equals(
                new String(channel, StandardCharsets.UTF_8)
        )) {
            subscriptionState.markSubscribed();
            log.atInfo().addKeyValue("event", "redis_subscription")
                    .addKeyValue("result", "ready").log("WebSocket Redis subscription ready.");
        }
    }

    @Override
    public void onChannelUnsubscribed(byte[] channel, long count) {
        if (properties.channel().equals(
                new String(channel, StandardCharsets.UTF_8)
        )) {
            log.atWarn().addKeyValue("event", "redis_subscription")
                    .addKeyValue("result", "unavailable").log("WebSocket Redis subscription unavailable.");
            sessionManager.closeConnectionsForRedisFailure();
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        byte[] body = message.getBody();
        if (body.length > MAX_EVENT_BYTES) {
            logRejected(WebSocketEventFailure.EVENT_TOO_LARGE.name());
            return;
        }

        WebSocketEvent event;
        try {
            event = objectMapper.readValue(body, WebSocketEvent.class);
        } catch (JacksonException exception) {
            logRejected(WebSocketEventFailure.INVALID_JSON.name());
            return;
        }

        try {
            validate(event);
        } catch (IllegalArgumentException exception) {
            logRejected(exception.getMessage());
            return;
        }

        try {
            dispatch(event);
        } catch (RuntimeException exception) {
            log.atWarn().addKeyValue("event", "websocket_event_dispatch")
                    .addKeyValue("result", "failure")
                    .addKeyValue("failure", WebSocketEventFailure.DELIVERY_FAILED.name())
                    .addKeyValue("event_id", event.eventId())
                    .addKeyValue("cause_type", exception.getClass().getName())
                    .log("WebSocket event dispatch failed.");
        }
    }

    private void logRejected(String failure) {
        log.atWarn().addKeyValue("event", "websocket_event_validation")
                .addKeyValue("result", "failure").addKeyValue("failure", failure)
                .log("WebSocket event rejected.");
    }

    private void dispatch(WebSocketEvent event) {
        String targetId = event.target().id().toString();
        switch (event.type()) {
            case AI_TASK -> messagingTemplate.convertAndSendToUser(targetId, "/queue/ai-tasks", event.payload());
            case COMMAND_RESULT -> messagingTemplate.convertAndSendToUser(targetId, "/queue/command-results", event.payload());
            case TRIP_EVENT -> messagingTemplate.convertAndSend("/topic/trips/" + targetId + "/events", event.payload());
        }
    }

    private void validate(WebSocketEvent event) {
        Assert.notNull(event, WebSocketEventFailure.EVENT_MISSING.name());
        Assert.notNull(event.eventId(), WebSocketEventFailure.EVENT_ID_MISSING.name());
        Assert.notNull(event.type(), WebSocketEventFailure.EVENT_TYPE_MISSING.name());
        Assert.isTrue(event.schemaVersion() == 1, WebSocketEventFailure.SCHEMA_VERSION_UNSUPPORTED.name());
        Assert.notNull(event.occurredAt(), WebSocketEventFailure.OCCURRED_AT_MISSING.name());
        Assert.notNull(event.target(), WebSocketEventFailure.TARGET_MISSING.name());
        Assert.notNull(event.target().type(), WebSocketEventFailure.TARGET_TYPE_MISSING.name());
        Assert.isTrue(event.target().id() != null && event.target().id() > 0,
                WebSocketEventFailure.TARGET_ID_INVALID.name());
        WebSocketEventTargetType expectedTarget = switch (event.type()) {
            case AI_TASK, COMMAND_RESULT -> WebSocketEventTargetType.USER;
            case TRIP_EVENT -> WebSocketEventTargetType.TRIP;
        };
        Assert.isTrue(event.target().type() == expectedTarget, WebSocketEventFailure.TARGET_TYPE_MISMATCH.name());
        Assert.notNull(event.payload(), WebSocketEventFailure.PAYLOAD_MISSING.name());
        Assert.isTrue(event.payload().isObject(), WebSocketEventFailure.PAYLOAD_FORMAT_INVALID.name());
        Assert.isTrue(event.payload().path("message").isTextual()
                        && StringUtils.hasText(event.payload().path("message").asString()),
                WebSocketEventFailure.PAYLOAD_MESSAGE_INVALID.name());
        Assert.isTrue(event.payload().has("data"), WebSocketEventFailure.PAYLOAD_DATA_MISSING.name());
    }
}
