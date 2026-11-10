package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.trip.entity.Trip;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class StoryPhotoChangeService {

    private final StoryRepository storyRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void invalidateCurrentStory(Trip trip, LocalDateTime deletedAt) {
        storyRepository.findCurrentCompletedByTripId(trip.getId()).ifPresent(story -> {
            story.softDelete(deletedAt);
            trip.changeCurrentStory(null);
        });
    }
}