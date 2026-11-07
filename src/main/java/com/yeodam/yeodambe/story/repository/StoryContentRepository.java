package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.service.response.StoryReadContent;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface StoryContentRepository
        extends MongoRepository<StoryContentDocument, Long> {

    @Aggregation(pipeline = {
            "{ '$match': { '_id': ?0 } }",
            """
            { '$project': {
                'mood': 1,
                'storySummary': 1,
                'blocks': { '$filter': {
                    'input': '$blocks',
                    'as': 'block',
                    'cond': { '$eq': [{ '$ifNull': ['$$block.deletedAt', null] }, null] }
                } }
            } }
            """
    })
    Optional<StoryReadContent> findActiveContentById(Long storyId);
}
