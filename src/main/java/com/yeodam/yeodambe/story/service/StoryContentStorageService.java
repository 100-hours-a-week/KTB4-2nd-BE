package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StoryContentStorageService {

    private final StoryContentRepository contentRepository;

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
