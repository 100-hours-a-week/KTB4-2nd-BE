package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryNotFoundException;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.story.entity.StorySelectedPlace;
import com.yeodam.yeodambe.trip.entity.Trip;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import com.mongodb.client.result.UpdateResult;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mapping.MappingException;
import org.springframework.core.convert.ConversionException;
import org.springframework.dao.DataAccessResourceFailureException;
import com.yeodam.yeodambe.story.repository.StoryBlockReferenceQuery;
import com.yeodam.yeodambe.story.service.response.StoryContentBlockDetail;
import com.yeodam.yeodambe.story.service.response.StoryPreviewKeyBackfillBatch;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
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
    private final MongoTemplate mongo;
    private final StoryBlockReferenceQuery referenceQuery;
    private final StoryValidator validator;
    private final StoryGenerationJobRepository jobRepository;

    @Transactional(readOnly = true)
    public void migrateOne(Long storyId) {
        StoryContentDocument existing = storyContentRepository.findById(storyId)
                .orElse(null);

        StoryGenerationJob job = jobRepository.findByStoryId(storyId).orElse(null);
        boolean nativeContent = job != null && job.getPreparedContentMetadata() != null;
        if (!nativeContent && existing != null && existing.getInitialContentHash() != null) {
            throw nativeContentMismatch();
        }
        if (!nativeContent && existing != null
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

        if (nativeContent) {
            validateNativeContent(job, existing, story.getTripId());
            validateBackfillContent(existing);
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

        validateInitialContent(expected, saved);
    }

    private void validateInitialContent(StoryContentDocument expected, StoryContentDocument saved) {
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

    @Transactional(readOnly = true)
    public StoryPreviewKeyBackfillBatch backfillPreviewKeys(Long afterStoryId, int batchSize) {
        if (afterStoryId == null || afterStoryId < 0 || batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException(StoryGenerationInternalErrorMessage.BACKFILL_RANGE_INVALID.message());
        }
        Document idRange = new Document("$gt", afterStoryId)
                .append("$type", List.of("long", "int"));
        List<Document> documents = mongo.getCollection("story_contents")
                .find(new Document("_id", idRange))
                .sort(new Document("_id", 1))
                .limit(batchSize)
                .into(new ArrayList<>());
        List<Long> conflicts = new ArrayList<>();
        List<Long> invalid = new ArrayList<>();
        int updated = 0;
        int unchanged = 0;
        Long nextAfterStoryId = afterStoryId;
        for (Document raw : documents) {
            Long storyId = ((Number) raw.get("_id")).longValue();
            nextAfterStoryId = storyId;
            try {
                long nextVersion = nextVersion(raw);
                StoryContentDocument content = mongoConverter.read(StoryContentDocument.class, raw);
                validateBackfillContent(content);
                Story story = storyRepository.findById(storyId)
                        .orElseThrow(() -> new StoryDataIntegrityException(
                                StoryGenerationInternalErrorMessage.READ_DATA_INTEGRITY_FAILED));
                StoryGenerationJob job = jobRepository.findByStoryId(storyId).orElse(null);
                boolean nativeContent = job != null && job.getPreparedContentMetadata() != null;
                if (nativeContent) {
                    validateNativeContent(job, content, story.getTripId());
                } else if (content.getInitialContentHash() != null) {
                    throw nativeContentMismatch();
                }
                if (!nativeContent && (content.getVersion() == null || content.getVersion() == 0)) {
                    StoryContentDocument expected = documentFactory.create(
                            story, selectedPlaceRepository.findAllByStoryIdOrderByIdAsc(storyId),
                            storyBlockRepository.findAllByStoryIdOrderByOrderNumberAscIdAsc(storyId),
                            ZoneOffset.UTC
                    );
                    validateInitialContent(expected, content);
                }
                Document updates = previewKeyUpdates(story.getTripId(), content);
                if (updates.isEmpty()) {
                    unchanged++;
                } else if (updatePreviewKeys(raw, updates, nextVersion)) {
                    updated++;
                } else {
                    conflicts.add(storyId);
                }
            } catch (StoryDataIntegrityException | MappingException | ConversionException failure) {
                invalid.add(storyId);
            }
        }
        return new StoryPreviewKeyBackfillBatch(
                nextAfterStoryId, documents.size(), updated, unchanged,
                List.copyOf(conflicts), List.copyOf(invalid)
        );
    }

    private void validateNativeContent(StoryGenerationJob job, StoryContentDocument content, Long tripId) {
        if (content == null) {
            throw new StoryDataIntegrityException(StoryGenerationInternalErrorMessage.CONTENT_DOCUMENT_MISSING);
        }
        String expectedHash;
        try {
            expectedHash = Document.parse(job.getPreparedContentMetadata()).getString("initialContentHash");
        } catch (IllegalArgumentException | ClassCastException failure) {
            throw nativeContentMismatch();
        }
        if (expectedHash == null || !expectedHash.matches("[0-9a-f]{64}")
                || !expectedHash.equals(content.getInitialContentHash())
                || !job.getStoryId().equals(content.getId()) || !job.getTripId().equals(tripId)) {
            throw nativeContentMismatch();
        }
    }

    private StoryDataIntegrityException nativeContentMismatch() {
        return new StoryDataIntegrityException(StoryGenerationInternalErrorMessage.PREPARATION_METADATA_INVALID);
    }

    private void validateBackfillContent(StoryContentDocument content) {
        if (content.getBlocks() == null || content.getNextBlockId() == null
                || content.getNextBlockId() < 1 || content.getMood() == null
                || content.getStorySummary() == null || content.getSelectedPlaceIds() == null
                || content.getBlocks().stream().anyMatch(block -> block == null
                || block.id() == null || block.id() < 1 || block.id() >= content.getNextBlockId())
                || content.getBlocks().stream().map(StoryContentBlock::id).distinct().count()
                != content.getBlocks().size()) {
            throw new StoryDataIntegrityException(StoryGenerationInternalErrorMessage.CONTENT_VERSION_INVALID);
        }
    }

    private long nextVersion(Document raw) {
        Object value = raw.get("version");
        if (value == null) {
            return 0L;
        }
        if (!(value instanceof Long) && !(value instanceof Integer)) {
            throw new StoryDataIntegrityException(StoryGenerationInternalErrorMessage.CONTENT_VERSION_INVALID);
        }
        long version = ((Number) value).longValue();
        if (version < 0 || version == Long.MAX_VALUE) {
            throw new StoryDataIntegrityException(StoryGenerationInternalErrorMessage.CONTENT_VERSION_INVALID);
        }
        return version + 1;
    }

    private Document previewKeyUpdates(Long tripId, StoryContentDocument content) {
        List<StoryContentBlock> active = content.getBlocks().stream()
                .filter(block -> block.deletedAt() == null)
                .toList();
        validator.validateBlockCount(active);
        List<StoryContentBlockDetail> details = referenceQuery.findByBlocks(active);
        validator.validateContentBlockRelations(tripId, details);
        List<StoryContentBlockDetail> visible = details.stream()
                .filter(detail -> detail.attachmentDeletedAt() == null && detail.fileDeletedAt() == null)
                .toList();
        validator.validateContentDisplayBlocks(tripId, visible);
        Map<Long, String> keys = visible.stream().collect(Collectors.toMap(
                detail -> detail.block().id(), StoryContentBlockDetail::currentPreviewStorageKey));
        Document updates = new Document();
        for (int index = 0; index < content.getBlocks().size(); index++) {
            StoryContentBlock block = content.getBlocks().get(index);
            String key = keys.get(block.id());
            if (key != null && !Objects.equals(key, block.previewStorageKey())) {
                updates.put("blocks." + index + ".previewStorageKey", key);
            }
        }
        return updates;
    }

    private boolean updatePreviewKeys(Document raw, Document updates, long nextVersion) {
        Document filter = new Document("_id", raw.get("_id"))
                .append("blocks", raw.get("blocks"));
        if (!raw.containsKey("version")) {
            filter.put("version", new Document("$exists", false));
        } else if (raw.get("version") == null) {
            filter.put("version", new Document("$type", "null"));
        } else {
            filter.put("version", raw.get("version"));
        }
        updates.put("version", nextVersion);
        UpdateResult result = mongo.getCollection("story_contents")
                .updateOne(filter, new Document("$set", updates));
        if (!result.wasAcknowledged()) {
            throw new DataAccessResourceFailureException(
                    StoryGenerationInternalErrorMessage.READ_DATA_INTEGRITY_FAILED.message());
        }
        return result.getMatchedCount() == 1 && result.getModifiedCount() == 1;
    }

}
