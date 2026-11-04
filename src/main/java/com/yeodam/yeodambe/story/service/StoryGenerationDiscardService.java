package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class StoryGenerationDiscardService {

    private final StoryRepository storyRepository;

    @Transactional
    public void discardPreparedStory(StoryGenerationJob job) {
        if (job.getStatus() != StoryGenerationJob.Status.FAILED
                && job.getStatus() != StoryGenerationJob.Status.CANCELED) {
            return;
        }

        if (job.getStoryId() == null) {
            return;
        }

        var story = storyRepository.findById(job.getStoryId())
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage
                                .PREPARED_STORY_INVALID.message()
                ));

        if (!job.getTripId().equals(story.getTripId())
                || story.getProcessingStatus() != Story.Status.PROCESSING) {
            throw new IllegalStateException(
                    StoryGenerationInternalErrorMessage
                            .PREPARED_STORY_INVALID.message()
            );
        }

        story.softDelete(LocalDateTime.now());
    }
}