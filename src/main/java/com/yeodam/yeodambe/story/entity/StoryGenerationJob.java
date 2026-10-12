package com.yeodam.yeodambe.story.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;

import java.time.LocalDateTime;

@Entity
@Table(name = "story_generation_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoryGenerationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "generation_id")
    private Long id;

    @Column(name = "execution_id", nullable = false, length = 36)
    private String executionId;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mood", nullable = false, length = 20)
    private Story.Mood mood;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.QUEUED;

    @Column(name = "progress", nullable = false)
    private int progress = 0;

    @Column(name = "story_id")
    private Long storyId;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public StoryGenerationJob(
            String executionId,
            Long tripId,
            Long userId,
            Story.Mood mood
    ) {
        this.executionId = executionId;
        this.tripId = tripId;
        this.userId = userId;
        this.mood = mood;
    }

    public boolean isFinished() {
        return status == Status.COMPLETED
                || status == Status.FAILED
                || status == Status.CANCELED;
    }

    public void updateProcessing(int progress) {
        if (isFinished()) {
            return;
        }

        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException(
                    StoryGenerationInternalErrorMessage.JOB_PROGRESS_INVALID.message()
            );
        }

        this.status = Status.PROCESSING;
        this.progress = Math.max(this.progress, progress);
    }

    public void complete(Long storyId) {
        if (isFinished()) {
            return;
        }

        if (storyId == null || storyId <= 0) {
            throw new IllegalArgumentException(
                    StoryGenerationInternalErrorMessage.JOB_STORY_ID_REQUIRED.message()
            );
        }

        this.status = Status.COMPLETED;
        this.progress = 100;
        this.storyId = storyId;
        this.errorCode = null;
    }

    public void cancel() {
        if (isFinished()) {
            return;
        }

        this.status = Status.CANCELED;
        this.errorCode = null;
    }

    public void fail(String errorCode) {
        if (isFinished()) {
            return;
        }

        this.status = Status.FAILED;
        this.errorCode = errorCode;
    }

    public enum Status {
        QUEUED,
        PROCESSING,
        COMPLETED,
        FAILED,
        CANCELED
    }
}