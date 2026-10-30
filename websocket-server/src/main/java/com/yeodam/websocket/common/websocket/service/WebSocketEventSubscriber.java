package com.yeodam.websocket.common.websocket.service;

import lombok.extern.slf4j.Slf4j;
import com.yeodam.websocket.common.response.SuccessMessage;
import com.yeodam.websocket.common.websocket.exception.WebSocketEventFailure;
import com.yeodam.websocket.common.websocket.exception.WebSocketEventValidationException;
import com.yeodam.websocket.common.websocket.service.request.WebSocketEvent;
import com.yeodam.websocket.common.websocket.service.request.WebSocketEventTargetType;
import com.yeodam.websocket.common.websocket.config.WebSocketEventProperties;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.context.ApplicationEventPublisher;
import com.yeodam.websocket.trip.service.TripPermissionChanged;
import com.yeodam.websocket.trip.service.TripPresenceService;
import org.springframework.context.annotation.Lazy;

import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
public class WebSocketEventSubscriber
        implements MessageListener, SubscriptionListener {

    private static final int MAX_EVENT_BYTES = 64 * 1024;

    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketEventProperties properties;
    private final RedisSubscriptionState subscriptionState;
    private final WebSocketSessionManager sessionManager;
    private final ApplicationEventPublisher eventPublisher;
    private final TripPresenceService presenceService;

    public WebSocketEventSubscriber(
            ObjectMapper objectMapper,
            SimpMessagingTemplate messagingTemplate,
            WebSocketEventProperties properties,
            RedisSubscriptionState subscriptionState,
            WebSocketSessionManager sessionManager,
            ApplicationEventPublisher eventPublisher,
            @Lazy TripPresenceService presenceService
    ) {
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
        this.properties = properties;
        this.subscriptionState = subscriptionState;
        this.sessionManager = sessionManager;
        this.eventPublisher = eventPublisher;
        this.presenceService = presenceService;
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
            logRejected(WebSocketEventFailure.EVENT_TOO_LARGE);
            return;
        }

        WebSocketEvent event;
        try {
            event = objectMapper.readValue(body, WebSocketEvent.class);
        } catch (JacksonException exception) {
            logRejected(WebSocketEventFailure.INVALID_JSON);
            return;
        }

        try {
            validate(event);
        } catch (WebSocketEventValidationException exception) {
            logRejected(exception.failure());
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

    private void logRejected(WebSocketEventFailure failure) {
        log.atWarn().addKeyValue("event", "websocket_event_validation")
                .addKeyValue("result", "failure").addKeyValue("failure", failure.name())
                .log(failure.message());
    }

    private void dispatch(WebSocketEvent event) {
        String targetId = event.target().id().toString();
        if (event.type() == com.yeodam.websocket.common.websocket.service.request.WebSocketEventType.COMMAND_RESULT
                && SuccessMessage.TRIP_PERMISSION_CHANGED.name().equals(event.payload().path("message").asString())) {
            tools.jackson.databind.JsonNode tripId = event.payload().path("data").path("tripId");
            if (tripId.isIntegralNumber() && tripId.canConvertToLong() && tripId.asLong() > 0) {
                eventPublisher.publishEvent(new TripPermissionChanged(event.target().id(), tripId.asLong()));
            }
        }
        switch (event.type()) {
            case PRESENCE_SNAPSHOT -> presenceService.onSnapshotEvent(event);
            case AI_TASK -> messagingTemplate.convertAndSendToUser(targetId, "/queue/ai-tasks", event.payload());
            case COMMAND_RESULT -> messagingTemplate.convertAndSendToUser(targetId, "/queue/command-results", event.payload());
            case TRIP_EVENT -> messagingTemplate.convertAndSend("/topic/trips/" + targetId + "/events", event.payload());
        }
    }

    private void validate(WebSocketEvent event) {
        require(event != null, WebSocketEventFailure.EVENT_MISSING);
        require(event.eventId() != null, WebSocketEventFailure.EVENT_ID_MISSING);
        require(event.type() != null, WebSocketEventFailure.EVENT_TYPE_MISSING);
        require(event.schemaVersion() == 1, WebSocketEventFailure.SCHEMA_VERSION_UNSUPPORTED);
        require(event.occurredAt() != null, WebSocketEventFailure.OCCURRED_AT_MISSING);
        require(event.target() != null, WebSocketEventFailure.TARGET_MISSING);
        require(event.target().type() != null, WebSocketEventFailure.TARGET_TYPE_MISSING);
        require(event.target().id() != null && event.target().id() > 0,
                WebSocketEventFailure.TARGET_ID_INVALID);
        WebSocketEventTargetType expectedTarget = switch (event.type()) {
            case AI_TASK, COMMAND_RESULT -> WebSocketEventTargetType.USER;
            case TRIP_EVENT, PRESENCE_SNAPSHOT -> WebSocketEventTargetType.TRIP;
        };
        require(event.target().type() == expectedTarget, WebSocketEventFailure.TARGET_TYPE_MISMATCH);
        require(event.payload() != null, WebSocketEventFailure.PAYLOAD_MISSING);
        require(event.payload().isObject(), WebSocketEventFailure.PAYLOAD_FORMAT_INVALID);
        require(event.payload().path("message").isString()
                        && StringUtils.hasText(event.payload().path("message").asString()),
                WebSocketEventFailure.PAYLOAD_MESSAGE_INVALID);
        require(event.payload().has("data"), WebSocketEventFailure.PAYLOAD_DATA_MISSING);
    }

    private void require(boolean condition, WebSocketEventFailure failure) {
        if (!condition) {
            throw new WebSocketEventValidationException(failure);
        }
    }
}
