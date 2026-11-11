package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.service.response.StoryReadContent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
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

    @Query(value = "{ '_id': { '$gt': ?0 } }", fields = "{ '_id': 1 }", sort = "{ '_id': 1 }")
    List<StoryContentDocument> findCleanupBatch(long afterStoryId, Pageable pageable);
}
