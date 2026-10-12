package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.story.entity.StoryGenerationPlace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoryGenerationPlaceRepository
        extends JpaRepository<StoryGenerationPlace, Long> {

    List<StoryGenerationPlace> findAllByGenerationIdOrderByOrderNumberAsc(
            Long generationId
    );
}