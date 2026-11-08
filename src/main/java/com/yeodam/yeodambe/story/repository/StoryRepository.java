package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.service.response.StoryReadContext;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoryRepository extends JpaRepository<Story, Long> {
    @Query("""
            select new com.yeodam.yeodambe.story.service.response.StoryReadContext(
                trip.id, trip.userId, story.id
            )
            from Trip trip
            left join Story story on story.id = trip.currentStoryId
                and story.tripId = trip.id
                and story.deletedAt is null
                and story.processingStatus = 'COMPLETED'
            where trip.id = :tripId
                and trip.deletedAt is null
            """)
    Optional<StoryReadContext> findReadContextByTripId(Long tripId);

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
    Optional<Story> findOwnedCurrentCompletedForPreviewById(
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

    @Query("""
            select story
            from Story story
            join Trip trip on trip.currentStoryId = story.id
            where story.id = :storyId
              and story.tripId = trip.id
              and trip.userId = :userId
              and trip.deletedAt is null
              and story.deletedAt is null
              and story.processingStatus = 'COMPLETED'
            """)
    Optional<Story> findOwnedCurrentCompletedForSimilarById(
            Long storyId,
            Long userId
    );

    @Query("""
            select story.id
            from Story story
            join Trip trip on trip.currentStoryId = story.id
            where story.id > :afterId
              and story.tripId = trip.id
              and trip.deletedAt is null
              and story.deletedAt is null
              and story.processingStatus = 'COMPLETED'
            order by story.id asc
            """)
    List<Long> findContentMigrationTargetIds(
            @Param("afterId") Long afterId,
            Pageable pageable
    );
}
