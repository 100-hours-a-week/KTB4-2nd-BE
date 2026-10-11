package com.yeodam.yeodambe.story.entity;

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

    public void beginPublishing() {
        this.status = Status.PUBLISHING;
        this.attemptCount++;
        this.lastError = null;
    }

    public void publicationSucceeded(LocalDateTime publishedAt) {
        this.status = Status.PUBLISHED;
        this.publishedAt = publishedAt;
        this.lastError = null;
    }

    public void publicationFailed(String error) {
        this.status = Status.PENDING;
        this.lastError = error;
    }

    public enum Status {
        PENDING,
        PUBLISHING,
        PUBLISHED
    }
}
