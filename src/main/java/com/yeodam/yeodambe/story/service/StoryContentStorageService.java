package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.exception.InvalidStoryResultException;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StoryContentStorageService {

    private final StoryContentRepository contentRepository;

    public StoryContentDocument insertPreparedIfAbsent(StoryContentDocument expected) {
        if (expected.getInitialContentHash() == null
                || !expected.getInitialContentHash().matches("[0-9a-f]{64}")) {
            throw new InvalidStoryResultException(StoryGenerationInternalErrorMessage.PREPARED_CONTENT_MISMATCH);
        }
        try {
            return contentRepository.insert(expected);
        } catch (DuplicateKeyException duplicate) {
            StoryContentDocument existing = contentRepository.findById(expected.getId())
                    .orElseThrow(() -> duplicate);
            if (!expected.getInitialContentHash().equals(existing.getInitialContentHash())) {
                throw new InvalidStoryResultException(StoryGenerationInternalErrorMessage.PREPARED_CONTENT_MISMATCH);
            }
            return existing;
        }
    }

    public boolean insertIfAbsent(StoryContentDocument document) {
        if (contentRepository.existsById(document.getId())) {
            return false;
        }

        try {
            contentRepository.insert(document);
            return true;
        } catch (DuplicateKeyException exception) {
            if (!contentRepository.existsById(document.getId())) {
                throw exception;
            }
            return false;
        }
    }
}
