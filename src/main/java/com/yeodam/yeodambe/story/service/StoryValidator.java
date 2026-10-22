package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.story.repository.StoryBlockRepository.BlockDetail;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StoryValidator {

    public void validateBlockCount(List<BlockDetail> blocks) {
        if (blocks.size() > 30) {
            throw new StoryDataIntegrityException(
                    StoryGenerationInternalErrorMessage.BLOCK_COUNT_EXCEEDED
            );
        }
    }

    public void validateBlockRelations(Long tripId, List<BlockDetail> blocks) {
        blocks.forEach(block -> validateBlockRelation(tripId, block));
    }

    public void validateDisplayBlocks(Long tripId, List<BlockDetail> blocks) {
        blocks.forEach(block -> validateDisplayBlock(tripId, block));
    }

    private void validateBlockRelation(Long tripId, BlockDetail block) {
        if (block.getAttachment() == null) {
            throw new StoryDataIntegrityException(
                    StoryGenerationInternalErrorMessage.BLOCK_ATTACHMENT_MISSING
            );
        }
        if (!tripId.equals(block.getAttachment().getTripId())) {
            throw new StoryDataIntegrityException(
                    StoryGenerationInternalErrorMessage.BLOCK_ATTACHMENT_TRIP_MISMATCH
            );
        }
        if (block.getFile() == null) {
            throw new StoryDataIntegrityException(
                    StoryGenerationInternalErrorMessage.BLOCK_FILE_MISSING
            );
        }
    }

    private void validateDisplayBlock(Long tripId, BlockDetail block) {
        if (block.getPlace() == null) {
            throw new StoryDataIntegrityException(
                    StoryGenerationInternalErrorMessage.DISPLAY_PLACE_MISSING
            );
        }
        if (!tripId.equals(block.getPlace().getTripId())) {
            throw new StoryDataIntegrityException(
                    StoryGenerationInternalErrorMessage.DISPLAY_PLACE_TRIP_MISMATCH
            );
        }
        if (block.getBlock().getSourceDate() == null) {
            throw new StoryDataIntegrityException(
                    StoryGenerationInternalErrorMessage.DISPLAY_DATE_MISSING
            );
        }
        String key = block.getAttachment().getPreviewStorageKey();
        if (key == null || key.isBlank()) {
            throw new StoryDataIntegrityException(
                    StoryGenerationInternalErrorMessage.DISPLAY_PREVIEW_KEY_MISSING
            );
        }
    }
}
