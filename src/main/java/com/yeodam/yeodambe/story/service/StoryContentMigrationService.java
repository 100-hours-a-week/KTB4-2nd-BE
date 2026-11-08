package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryNotFoundException;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.story.entity.StorySelectedPlace;
import com.yeodam.yeodambe.trip.entity.Trip;
import java.util.List;
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
import java.util.Map;
import java.util.stream.Collectors;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.entity.TripAttachment;

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
    private final TripAttachmentRepository tripAttachmentRepository;

    @Transactional(readOnly = true)
    public void migrateOne(Long storyId) {
        StoryContentDocument existing = storyContentRepository.findById(storyId)
                .orElse(null);

        if (existing != null
                && existing.getVersion() != null
                && existing.getVersion() > 0) {
            return;
        }

        Story story = storyRepository.findById(storyId)
                .orElseThrow(StoryNotFoundException::new);

        Trip trip = story.getTrip();

        if (story.getDeletedAt() != null
                || story.getProcessingStatus() != Story.Status.COMPLETED
                || trip.getDeletedAt() != null
                || !storyId.equals(trip.getCurrentStoryId())) {
            return;
        }

        List<StorySelectedPlace> selectedPlaces = selectedPlaceRepository
                .findAllByStoryIdOrderByIdAsc(storyId);

        List<StoryBlock> blocks = storyBlockRepository
                .findAllByStoryIdOrderByOrderNumberAscIdAsc(storyId);

        StoryContentDocument document = documentFactory.create(
                story,
                selectedPlaces,
                blocks,
                ZoneOffset.UTC,
                loadPreviewKeys(blocks)
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

        removePreviewKeys(expectedData);
        removePreviewKeys(savedData);
        if (!expectedData.equals(savedData)) {
            throw new StoryDataIntegrityException(
                    StoryGenerationInternalErrorMessage.MIGRATION_CONTENT_MISMATCH
            );
        }
    }
    private Map<Long, String> loadPreviewKeys(List<StoryBlock> blocks) {
        if (blocks.isEmpty()) {
            return Map.of();
        }
        return tripAttachmentRepository.findAllWithFileByIds(blocks.stream()
                        .map(StoryBlock::getTripAttachmentId)
                        .distinct()
                        .toList()).stream()
                .filter(attachment -> attachment.getPreviewStorageKey() != null)
                .collect(Collectors.toMap(
                        TripAttachment::getId,
                        TripAttachment::getPreviewStorageKey
                ));
    }

    private void removePreviewKeys(Document data) {
        List<Document> blocks = data.getList("blocks", Document.class);
        if (blocks != null) {
            blocks.forEach(block -> block.remove("previewStorageKey"));
        }
    }

}
