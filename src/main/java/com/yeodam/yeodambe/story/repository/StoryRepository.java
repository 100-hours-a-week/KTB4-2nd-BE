package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.story.entity.Story;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface StoryRepository extends JpaRepository<Story, Long> {
    @Query("""
            SELECT s FROM Story s JOIN Trip t ON t.currentStoryId = s.id
            WHERE t.id = :tripId AND s.tripId = :tripId
              AND s.deletedAt IS NULL AND s.processingStatus = 'COMPLETED'
            """)
    Optional<Story> findCurrentCompletedByTripId(Long tripId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select story
            from Story story
            join Trip trip on trip.currentStoryId = story.id
            where story.id = :storyId
              and story.tripId = trip.id
              and trip.userId = :userId
              and trip.deletedAt is null
              and trip.processingStatus = 'COMPLETED'
              and story.deletedAt is null
              and story.processingStatus = 'COMPLETED'
            """)
    Optional<Story> findOwnedCurrentCompletedById(
            Long storyId,
            Long userId
    );

    @Query("""
            select story.tripId
            from Story story
            where story.id = :storyId
            """)
    Optional<Long> findTripIdByStoryId(Long storyId);

    @Query("""
            select count(story)
            from Story story
            join Trip trip on trip.currentStoryId = story.id
            where trip.userId = :userId
              and trip.processingStatus = 'COMPLETED'
              and trip.deletedAt is null
              and story.tripId = trip.id
              and story.processingStatus = 'COMPLETED'
              and story.deletedAt is null
            """)
    long countCurrentCompletedByUserId(Long userId);
}
