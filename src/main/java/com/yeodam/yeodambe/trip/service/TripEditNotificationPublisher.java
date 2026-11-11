package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.websocket.service.WebSocketEventPublisher;
import com.yeodam.yeodambe.trip.entity.TripEditNotificationOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(propagation = Propagation.NEVER)
public class TripEditNotificationPublisher {

    private final TripEditNotificationPersistenceService persistence;
    private final WebSocketEventPublisher eventPublisher;
    private final Clock clock;
    private final TripAttachmentStorageClient storage;
    private final ObjectMapper json;

    @Scheduled(fixedDelay = 1_000)
    public void retryPending() {
        List<Long> ids;
        try {
            ids = persistence.findDueIds(now());
        } catch (RuntimeException exception) {
            logFailure("load_batch", null, exception);
            return;
        }
        for (Long id : ids) {
            publish(id);
        }
    }

    public void publish(Long id) {
        Optional<TripEditNotificationOutbox> pending;
        try {
            pending = persistence.findDue(id, now());
        } catch (RuntimeException exception) {
            logFailure("load", id, exception);
            return;
        }
        if (pending.isEmpty()) {
            return;
        }
        TripEditNotificationOutbox notification = pending.orElseThrow();
        try {
            eventPublisher.publishSerialized(prepareForDelivery(notification));
        } catch (RuntimeException exception) {
            logFailure("publish", id, exception);
            recordFailure(notification);
            return;
        }
        try {
            persistence.markPublished(id, now());
        } catch (RuntimeException exception) {
            logFailure("record_success", id, exception);
        }
    }

    private String prepareForDelivery(TripEditNotificationOutbox notification) {
        if (notification.getThumbnailKey() == null
                && !notification.getEventJson().contains("\"TRIP_PHOTOS_RESTORED\"")) {
            return notification.getEventJson();
        }
        JsonNode message = json.readTree(notification.getEventJson());
        JsonNode data = message.path("payload").path("data");
        if ("TRIP_PHOTOS_RESTORED".equals(message.path("payload").path("message").asString())) {
            addThumbnailUrl(data);
            data.path("places").forEach(this::addThumbnailUrl);
            data.path("unclassifiedFolders").forEach(this::addThumbnailUrl);
            return json.writeValueAsString(message);
        }
        JsonNode item = message.path("payload").path("data").path("listItem");
        if (item instanceof ObjectNode listItem) {
            listItem.put("thumbnailUrl", storage.createReadUrl(notification.getThumbnailKey()));
        }
        return json.writeValueAsString(message);
    }

    private void addThumbnailUrl(JsonNode item) {
        if (item instanceof ObjectNode object) {
            JsonNode key = object.path("thumbnailKey");
            if (key.isTextual()) {
                object.put("thumbnailUrl", storage.createReadUrl(key.asString()));
            } else {
                object.putNull("thumbnailUrl");
            }
        }
    }

    private void recordFailure(TripEditNotificationOutbox notification) {
        try {
            persistence.markFailed(notification, now());
        } catch (RuntimeException exception) {
            logFailure("record_failure", notification.getId(), exception);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private void logFailure(String stage, Long id, RuntimeException exception) {
        log.atWarn().addKeyValue("event", "trip_edit_notification")
                .addKeyValue("notification_id", id)
                .addKeyValue("failure_stage", stage)
                .addKeyValue("failure", exception.getClass().getName())
                .log("공유 편집 알림 발행 실패. 미발행 기록을 보존합니다.");
    }
}
