package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface StoryBlockRepository extends JpaRepository<StoryBlock, Long> {
    @Query("""
            SELECT b AS block, a AS attachment, f AS file, p AS place
            FROM StoryBlock b
            LEFT JOIN b.attachment a
            LEFT JOIN a.file f
            LEFT JOIN a.tripPlace p
            WHERE b.storyId = :storyId AND b.deletedAt IS NULL
            ORDER BY b.orderNumber ASC, b.id ASC
            """)
    List<BlockDetail> findDetailsByStoryId(Long storyId);

    interface BlockDetail {
        StoryBlock getBlock();

        TripAttachment getAttachment();

        StoredFile getFile();

        TripDetailPlace getPlace();
    }
}
