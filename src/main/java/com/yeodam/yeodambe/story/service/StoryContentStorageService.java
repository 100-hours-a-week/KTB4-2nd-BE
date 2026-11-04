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

    public void insertIfAbsent(StoryContentDocument document) {
        if (contentRepository.existsById(document.getId())) {
            return;
        }

        try {
            contentRepository.insert(document);
        } catch (DuplicateKeyException exception) {
            if (!contentRepository.existsById(document.getId())) {
                throw exception;
            }
        }
    }
}
