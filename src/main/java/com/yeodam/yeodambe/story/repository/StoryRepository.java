package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.story.entity.Story;
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
}
