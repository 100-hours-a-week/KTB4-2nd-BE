package com.yeodam.yeodambe.story.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "story_generation_places")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoryGenerationPlace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "generation_place_id")
    private Long id;

    @Column(name = "generation_id", nullable = false)
    private Long generationId;

    @Column(name = "trip_place_id", nullable = false)
    private Long tripPlaceId;

    @Column(name = "order_number", nullable = false)
    private int orderNumber;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public StoryGenerationPlace(
            Long generationId,
            Long tripPlaceId,
            int orderNumber
    ) {
        this.generationId = generationId;
        this.tripPlaceId = tripPlaceId;
        this.orderNumber = orderNumber;
    }
}