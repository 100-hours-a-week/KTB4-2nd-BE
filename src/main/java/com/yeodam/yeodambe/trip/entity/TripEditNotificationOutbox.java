package com.yeodam.yeodambe.trip.entity;

import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEvent;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTargetType;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "trip_edit_notification_outbox", indexes = @Index(
        name = "idx_trip_edit_notification_due", columnList = "status,next_attempt_at,notification_id"
))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TripEditNotificationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false, length = 36)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 30)
    private WebSocketEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, updatable = false, length = 10)
    private WebSocketEventTargetType targetType;

    @Column(name = "target_id", nullable = false, updatable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long targetId;

    @Column(name = "event_json", nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String eventJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    @Column(name = "next_attempt_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime nextAttemptAt;

    @Column(name = "published_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime publishedAt;

    public TripEditNotificationOutbox(WebSocketEvent event, String eventJson, LocalDateTime createdAt) {
        this.eventId = event.eventId().toString();
        this.eventType = event.type();
        this.targetType = event.target().type();
        this.targetId = event.target().id();
        this.eventJson = eventJson;
        this.status = Status.PENDING;
        this.createdAt = createdAt;
        this.nextAttemptAt = createdAt;
    }

    public enum Status {
        PENDING,
        PUBLISHED
    }
}
