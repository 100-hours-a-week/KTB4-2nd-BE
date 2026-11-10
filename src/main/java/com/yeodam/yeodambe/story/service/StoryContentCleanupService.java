package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StoryContentCleanupService {
    private static final int BATCH_SIZE = 100;

    private final StoryRepository stories;
    private final StoryContentRepository contents;
    private long afterStoryId;

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    void cleanupBatch() {
        var storyIds = contents.findCleanupBatch(afterStoryId, PageRequest.of(0, BATCH_SIZE)).stream()
                .map(StoryContentDocument::getId)
                .toList();
        if (!storyIds.isEmpty()) {
            var deletedIds = stories.findDeletedContentIds(storyIds);
            if (!deletedIds.isEmpty()) {
                contents.deleteAllById(deletedIds);
            }
        }
        afterStoryId = storyIds.size() == BATCH_SIZE ? storyIds.getLast() : 0L;
    }
}
