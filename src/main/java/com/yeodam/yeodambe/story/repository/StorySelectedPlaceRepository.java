package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.story.entity.StorySelectedPlace;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorySelectedPlaceRepository
        extends JpaRepository<StorySelectedPlace, Long> {
}