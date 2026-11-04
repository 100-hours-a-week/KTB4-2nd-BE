package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.story.entity.StorySelectedPlace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StorySelectedPlaceRepository
        extends JpaRepository<StorySelectedPlace, Long> {
    List<StorySelectedPlace> findAllByStoryIdOrderByIdAsc(Long storyId);
}