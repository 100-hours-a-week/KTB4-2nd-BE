package com.yeodam.yeodambe.story.entity;

import org.springframework.util.Assert;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "story_generation_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoryGenerationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "outbox_id")
    private Long id;

    @Column(name = "generation_id", nullable = false)
    private Long generationId;

    @Column(name = "payload", nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Column(name = "request_id", nullable = false, length = 128)
    private String requestId;

    @Column(name = "trip_id", updatable = false)
    private Long tripId;

    @Column(name = "topic", updatable = false)
    private String topic;

    @Column(name = "event_source", nullable = false, updatable = false)
    private String eventSource = "story_generation_outbox";

    @Column(name = "cdc_managed", nullable = false, updatable = false)
    private boolean cdcManaged;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public StoryGenerationOutbox(Long generationId, String payload, String requestId) {
        this.generationId = generationId;
        this.payload = payload;
        this.requestId = requestId;
    }

    public void enableCdc(Long tripId, String topic) {
        Assert.notNull(tripId, "Outbox tripId is required");
        Assert.hasText(topic, "Outbox topic is required");
        this.tripId = tripId;
        this.topic = topic;
        this.cdcManaged = true;
    }

    public void publicationSucceeded(LocalDateTime publishedAt) {
        this.status = Status.PUBLISHED;
        if (this.publishedAt == null) {
            this.publishedAt = publishedAt;
        }
        this.lastError = null;
    }

    public enum Status {
        PENDING,
        PUBLISHING,
        PUBLISHED
    }
}
