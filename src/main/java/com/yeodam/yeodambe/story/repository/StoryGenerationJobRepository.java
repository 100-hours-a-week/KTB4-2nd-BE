package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoryGenerationJobRepository
        extends JpaRepository<StoryGenerationJob, Long> {
    boolean existsByTripIdAndStatusIn(
            Long tripId,
            List<StoryGenerationJob.Status> statuses
    );
}