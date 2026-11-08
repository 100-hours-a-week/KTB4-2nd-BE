package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.common.exception.AttachmentCannotBeReusedException;
import com.yeodam.yeodambe.common.exception.InvalidStoryUpdateException;
import com.yeodam.yeodambe.story.repository.StoryBlockRepository.BlockDetail;
import com.yeodam.yeodambe.story.service.request.StoryUpdateRequest;
import org.springframework.stereotype.Component;
import com.yeodam.yeodambe.common.exception.StoryOrAttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.StoryTonePreviewTakenAtMissingException;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.story.service.request.StoryTonePreviewRequest;
import com.yeodam.yeodambe.story.service.response.StoryContentBlockDetail;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

@Component
public class StoryValidator {

    public void validateBlockCount(List<?> blocks) {
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

    public void validateContentBlockRelations(
            Long tripId,
            List<StoryContentBlockDetail> blocks
    ) {
        for (StoryContentBlockDetail detail : blocks) {
            if (detail.attachmentId() == null) {
                throw new StoryDataIntegrityException(
                        StoryGenerationInternalErrorMessage.BLOCK_ATTACHMENT_MISSING
                );
            }
            if (!tripId.equals(detail.attachmentTripId())) {
                throw new StoryDataIntegrityException(
                        StoryGenerationInternalErrorMessage.BLOCK_ATTACHMENT_TRIP_MISMATCH
                );
            }
            if (detail.fileId() == null) {
                throw new StoryDataIntegrityException(
                        StoryGenerationInternalErrorMessage.BLOCK_FILE_MISSING
                );
            }
        }
    }

    public void validateContentDisplayBlocks(
            Long tripId,
            List<StoryContentBlockDetail> blocks
    ) {
        for (StoryContentBlockDetail detail : blocks) {
            if (detail.placeId() == null) {
                throw new StoryDataIntegrityException(
                        StoryGenerationInternalErrorMessage.DISPLAY_PLACE_MISSING
                );
            }
            if (!tripId.equals(detail.placeTripId())) {
                throw new StoryDataIntegrityException(
                        StoryGenerationInternalErrorMessage.DISPLAY_PLACE_TRIP_MISMATCH
                );
            }
            if (detail.block().sourceDate() == null) {
                throw new StoryDataIntegrityException(
                        StoryGenerationInternalErrorMessage.DISPLAY_DATE_MISSING
                );
            }
            String key = detail.currentPreviewStorageKey();
            if (key == null || key.isBlank()) {
                throw new StoryDataIntegrityException(
                        StoryGenerationInternalErrorMessage.DISPLAY_PREVIEW_KEY_MISSING
                );
            }
        }
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

    public void validatePreviewAttachments(
            Long tripId,
            int requestedCount,
            List<TripAttachment> attachments
    ) {
        boolean invalidAttachments =
                attachments.size() != requestedCount
                        || attachments.stream().anyMatch(attachment ->
                        !tripId.equals(attachment.getTripId())
                                || attachment.getClassificationStatus()
                                != ClassificationStatus.ACTIVE
                );

        if (invalidAttachments) {
            throw new StoryOrAttachmentNotFoundException();
        }

        boolean missingTakenAt = attachments.stream()
                .anyMatch(attachment -> attachment.getTakenAt() == null);

        if (missingTakenAt) {
            throw new StoryTonePreviewTakenAtMissingException();
        }
    }

    public void validatePreviewBlockIds(
            List<StoryTonePreviewRequest.Block> requestedBlocks,
            Set<Long> existingBlockIds
    ) {
        for (StoryTonePreviewRequest.Block requested : requestedBlocks) {
            Long blockId = requested.storyBlockId();

            if (blockId != null && !existingBlockIds.contains(blockId)) {
                throw new StoryOrAttachmentNotFoundException();
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
