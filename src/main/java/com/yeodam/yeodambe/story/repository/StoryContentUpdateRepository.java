package com.yeodam.yeodambe.story.repository;

import com.mongodb.client.result.UpdateResult;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class StoryContentUpdateRepository {

    private final MongoTemplate mongo;
    private final StoryContentRepository contentRepository;

    public void saveChanges(
            StoryContentDocument content,
            List<StoryContentBlock> previousBlocks
    ) {
        List<StoryContentBlock> updatedBlocks = content.getBlocks();
        if (content.getVersion() == null || !hasSameStructure(previousBlocks, updatedBlocks)) {
            contentRepository.save(content);
            return;
        }

        Query query = Query.query(Criteria.where("_id").is(content.getId())
                .and("version").is(content.getVersion()));
        Update update = textChanges(content, previousBlocks, updatedBlocks);
        UpdateResult result = mongo.updateFirst(query, update, StoryContentDocument.class);
        if (!result.wasAcknowledged()) {
            throw new DataAccessResourceFailureException(
                    StoryGenerationInternalErrorMessage.CONTENT_UPDATE_UNACKNOWLEDGED.message()
            );
        }
        if (result.getMatchedCount() != 1) {
            throw new OptimisticLockingFailureException(
                    StoryGenerationInternalErrorMessage.CONTENT_UPDATE_CONFLICT.message()
            );
        }
    }

    private boolean hasSameStructure(
            List<StoryContentBlock> previousBlocks,
            List<StoryContentBlock> updatedBlocks
    ) {
        if (previousBlocks.size() != updatedBlocks.size()) {
            return false;
        }
        for (int index = 0; index < previousBlocks.size(); index++) {
            if (!hasSameStructure(previousBlocks.get(index), updatedBlocks.get(index))) {
                return false;
            }
        }
        return true;
    }

    private boolean hasSameStructure(StoryContentBlock previous, StoryContentBlock updated) {
        return Objects.equals(previous.id(), updated.id())
                && Objects.equals(previous.tripAttachmentId(), updated.tripAttachmentId())
                && Objects.equals(previous.sourceTripPlaceId(), updated.sourceTripPlaceId())
                && Objects.equals(previous.sourceDate(), updated.sourceDate())
                && Objects.equals(previous.createdAt(), updated.createdAt())
                && Objects.equals(previous.deletedAt(), updated.deletedAt())
                && Objects.equals(previous.previewStorageKey(), updated.previewStorageKey());
    }

    private Update textChanges(
            StoryContentDocument content,
            List<StoryContentBlock> previousBlocks,
            List<StoryContentBlock> updatedBlocks
    ) {
        Update update = new Update()
                .set("mood", content.getMood())
                .set("storySummary", content.getStorySummary())
                .inc("version", 1L);
        for (int index = 0; index < updatedBlocks.size(); index++) {
            StoryContentBlock previous = previousBlocks.get(index);
            StoryContentBlock updated = updatedBlocks.get(index);
            String path = "blocks." + index + ".";
            setIfChanged(update, path + "dayLabel", previous.dayLabel(), updated.dayLabel());
            setIfChanged(update, path + "detailSummary", previous.detailSummary(), updated.detailSummary());
            setIfChanged(update, path + "memo", previous.memo(), updated.memo());
            setIfChanged(update, path + "updatedAt", previous.updatedAt(), updated.updatedAt());
        }
        return update;
    }

    private void setIfChanged(Update update, String path, Object previous, Object updated) {
        if (!Objects.equals(previous, updated)) {
            update.set(path, updated);
        }
    }
}
