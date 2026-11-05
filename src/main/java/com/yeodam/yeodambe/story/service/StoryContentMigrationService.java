package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryNotFoundException;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.repository.StoryBlockRepository;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.repository.StorySelectedPlaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import org.bson.Document;
import org.springframework.data.mongodb.core.convert.MongoConverter;

import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class StoryContentMigrationService {

    private final StoryRepository storyRepository;
    private final StoryBlockRepository storyBlockRepository;
    private final StorySelectedPlaceRepository selectedPlaceRepository;
    private final StoryContentRepository storyContentRepository;
    private final StoryContentDocumentFactory documentFactory;
    private final StoryContentStorageService storageService;
    private final MongoConverter mongoConverter;

    @Transactional(readOnly = true)
    public void migrateOne(Long storyId) {
        var existing = storyContentRepository.findById(storyId)
                .orElse(null);

        if (existing != null
                && existing.getVersion() != null
                && existing.getVersion() > 0) {
            return;
        }

        Story story = storyRepository.findById(storyId)
                .orElseThrow(StoryNotFoundException::new);

        var trip = story.getTrip();

        if (story.getDeletedAt() != null
                || story.getProcessingStatus() != Story.Status.COMPLETED
                || trip.getDeletedAt() != null
                || !storyId.equals(trip.getCurrentStoryId())) {
            return;
        }

        var selectedPlaces = selectedPlaceRepository
                .findAllByStoryIdOrderByIdAsc(storyId);

        var blocks = storyBlockRepository
                .findAllByStoryIdOrderByOrderNumberAscIdAsc(storyId);

        var document = documentFactory.create(
                story,
                selectedPlaces,
                blocks,
                ZoneOffset.UTC
        );

        storageService.insertIfAbsent(document);
        validateSavedDocument(document);
    }
    private void validateSavedDocument(StoryContentDocument expected) {
        StoryContentDocument saved = storyContentRepository
                .findById(expected.getId())
                .orElseThrow(() -> new StoryDataIntegrityException(
                        StoryGenerationInternalErrorMessage.CONTENT_DOCUMENT_MISSING
                ));

        if (saved.getVersion() != null && saved.getVersion() > 0) {
            return;
        }

        Document expectedData = new Document();
        Document savedData = new Document();

        mongoConverter.write(expected, expectedData);
        mongoConverter.write(saved, savedData);

        expectedData.remove("version");
        savedData.remove("version");

        if (!expectedData.equals(savedData)) {
            throw new StoryDataIntegrityException(
                    StoryGenerationInternalErrorMessage.MIGRATION_CONTENT_MISMATCH
            );
        }
    }
}