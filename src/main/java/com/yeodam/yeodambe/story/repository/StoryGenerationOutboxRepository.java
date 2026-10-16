package com.yeodam.yeodambe.story.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.yeodam.yeodambe.story.entity.StoryGenerationOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StoryGenerationOutboxRepository
        extends JpaRepository<StoryGenerationOutbox, Long> {
    Optional<StoryGenerationOutbox> findByGenerationId(Long generationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select outbox from StoryGenerationOutbox outbox where outbox.id = :id")
    Optional<StoryGenerationOutbox> findByIdForUpdate(@Param("id") Long id);
}
