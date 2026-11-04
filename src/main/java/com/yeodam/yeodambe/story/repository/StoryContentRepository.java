package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.story.document.StoryContentDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface StoryContentRepository
        extends MongoRepository<StoryContentDocument, Long> {
}