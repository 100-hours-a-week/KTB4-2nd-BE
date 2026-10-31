package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StoryBlockRepository extends JpaRepository<StoryBlock, Long> {
    @Query("""
            SELECT b AS block, a AS attachment, f AS file, p AS place
            FROM StoryBlock b
            LEFT JOIN b.attachment a
            LEFT JOIN a.file f
            LEFT JOIN TripDetailPlace p ON p.id = b.sourceTripPlaceId
            WHERE b.storyId = :storyId AND b.deletedAt IS NULL
            ORDER BY b.orderNumber ASC, b.id ASC
            """)
    List<BlockDetail> findDetailsByStoryId(Long storyId);

    @Query("""
            select block
            from StoryBlock block
            join fetch block.attachment attachment
            join attachment.file file
            join block.story story
            join story.trip trip
            join attachment.tripPlace place
            where block.id = :storyBlockId
              and block.storyId = :storyId
              and block.deletedAt is null
              and story.processingStatus = 'COMPLETED'
              and story.deletedAt is null
              and trip.currentStoryId = story.id
              and trip.userId = :userId
              and trip.deletedAt is null
              and attachment.tripId = trip.id
              and attachment.classificationStatus =
                  com.yeodam.yeodambe.trip.entity.ClassificationStatus.ACTIVE
              and attachment.deletedAt is null
              and file.deletedAt is null
              and place.tripId = trip.id
              and place.deletedAt is null
            """)
    Optional<StoryBlock> findOwnedCurrentCompletedBlock(
            @Param("storyId") Long storyId,
            @Param("storyBlockId") Long storyBlockId,
            @Param("userId") Long userId
    );

    List<StoryBlock> findAllByStoryId(Long storyId);

    interface BlockDetail {
        StoryBlock getBlock();

        TripAttachment getAttachment();

        StoredFile getFile();

        TripDetailPlace getPlace();
    }
}
