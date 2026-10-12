package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.story.entity.StoryGenerationOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StoryGenerationOutboxRepository
        extends JpaRepository<StoryGenerationOutbox, Long> {
    Optional<StoryGenerationOutbox> findByGenerationId(Long generationId);
}
