package com.yeodam.yeodambe.story.entity;

import com.yeodam.yeodambe.trip.entity.Trip;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "stories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Story {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "story_id")
    private Long id;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false, insertable = false, updatable = false)
    private Trip trip;

    @Enumerated(EnumType.STRING)
    @Column(name = "mood", nullable = false, length = 20)
    private Mood mood;

    @Column(name = "story_summary", nullable = false, length = 40)
    private String storySummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 20)
    private Status processingStatus = Status.PROCESSING;

    @OneToMany(mappedBy = "story", fetch = FetchType.LAZY)
    private List<StoryBlock> blocks = new ArrayList<>();

    @OneToMany(mappedBy = "story", fetch = FetchType.LAZY)
    private List<StorySelectedPlace> selectedPlaces = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public void softDelete(LocalDateTime deletedAt) {
        if (this.deletedAt == null) {
            this.deletedAt = deletedAt;
        }
    }

    public Story(
            Long tripId,
            Mood mood,
            String storySummary
    ) {
        if (tripId == null || mood == null) {
            throw new IllegalArgumentException("스토리 여행과 분위기가 필요합니다.");
        }
        validateText(storySummary);
        this.tripId = tripId;
        this.mood = mood;
        this.storySummary = storySummary;
    }

    static void validateText(String text) {
        if (text == null || text.isBlank() || text.length() > 40) {
            throw new IllegalArgumentException("스토리 문구는 필수이며 최대 40자입니다.");
        }
    }

    public void addBlock(StoryBlock block) {
        block.connectStory(this);
        if (!blocks.contains(block)) {
            blocks.add(block);
        }
    }

    public void addSelectedPlace(StorySelectedPlace place) {
        place.connectStory(this);
        if (!selectedPlaces.contains(place)) {
            selectedPlaces.add(place);
        }
    }

    public List<StoryBlock> getBlocks() {
        return Collections.unmodifiableList(blocks);
    }

    public List<StorySelectedPlace> getSelectedPlaces() {
        return Collections.unmodifiableList(selectedPlaces);
    }

    public enum Mood {
        PLAIN, EMOTIONAL, HUMOROUS, CALM, LITERARY
    }

    public enum Status {
        PROCESSING, COMPLETED, FAILED, CANCELED
    }
}
