package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface StoryGenerationJobRepository
        extends JpaRepository<StoryGenerationJob, Long> {
    boolean existsByTripIdAndStatusIn(
            Long tripId,
            List<StoryGenerationJob.Status> statuses
    );

    List<StoryGenerationJob> findAllByStatusIn(
            List<StoryGenerationJob.Status> statuses
    );

    Optional<StoryGenerationJob> findByExecutionId(
            String executionId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select job
        from StoryGenerationJob job
        where job.id = :generationId
        """)
    Optional<StoryGenerationJob> findByIdForUpdate(
            @Param("generationId") Long generationId
    );
}
