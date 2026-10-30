package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.common.exception.AttachmentCannotBeReusedException;
import com.yeodam.yeodambe.common.exception.InvalidStoryUpdateException;
import com.yeodam.yeodambe.story.repository.StoryBlockRepository.BlockDetail;
import com.yeodam.yeodambe.story.service.request.StoryUpdateRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

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

    public void validateUpdateBlocks(
            List<StoryUpdateRequest.Block> blocks
    ) {
        Set<Long> blockIds = new HashSet<>();
        Set<Long> attachmentIds = new HashSet<>();
        Set<Integer> orderNumbers = new HashSet<>();

        for (StoryUpdateRequest.Block block : blocks) {
            if (block.storyBlockId() != null
                    && !blockIds.add(block.storyBlockId())) {
                throw new InvalidStoryUpdateException();
            }

            if (!attachmentIds.add(block.tripAttachmentId())) {
                throw new AttachmentCannotBeReusedException();
            }

            int orderNumber = block.orderNumber();
            if (orderNumber < 1
                    || orderNumber > blocks.size()
                    || !orderNumbers.add(orderNumber)) {
                throw new InvalidStoryUpdateException();
            }
        }
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
