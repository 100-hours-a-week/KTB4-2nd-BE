package com.yeodam.yeodambe.story.entity;

import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "story_selected_places")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StorySelectedPlace {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "story_place_id")
    private Long id;

    @Column(name = "story_id", nullable = false)
    private Long storyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false, insertable = false, updatable = false)
    private Story story;

    @Column(name = "trip_place_id", nullable = false)
    private Long tripPlaceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_place_id", nullable = false, insertable = false, updatable = false)
    private TripDetailPlace tripPlace;

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

    public StorySelectedPlace(
            Long storyId,
            Long tripPlaceId
    ) {
        if (storyId == null || tripPlaceId == null) {
            throw new IllegalArgumentException("선택 장소 참조가 필요합니다.");
        }
        this.storyId = storyId;
        this.tripPlaceId = tripPlaceId;
    }

    void connectStory(Story story) {
        if (story.getId() == null || !story.getId().equals(storyId)) {
            throw new IllegalArgumentException("저장된 같은 스토리에만 연결할 수 있습니다.");
        }
        this.storyId = story.getId();
        this.story = story;
    }
}
