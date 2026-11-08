package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.story.entity.StorySelectedPlace;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class StoryContentDocumentFactory {

    public StoryContentDocument create(
            Story story,
            List<StorySelectedPlace> selectedPlaces,
            List<StoryBlock> blocks,
            ZoneId sourceZone
    ) {
        return create(story, selectedPlaces, blocks, sourceZone, Map.of());
    }

    public StoryContentDocument create(
            Story story,
            List<StorySelectedPlace> selectedPlaces,
            List<StoryBlock> blocks,
            ZoneId sourceZone,
            Map<Long, String> previewKeys
    ) {
        List<Long> selectedPlaceIds = new ArrayList<>();

        for (StorySelectedPlace place : selectedPlaces) {
            selectedPlaceIds.add(place.getTripPlaceId());
        }

        List<StoryContentBlock> documentBlocks = new ArrayList<>();
        long nextBlockId = 1;

        for (StoryBlock block : blocks) {
            documentBlocks.add(new StoryContentBlock(
                    block.getId(),
                    block.getTripAttachmentId(),
                    block.getSourceTripPlaceId(),
                    block.getSourceDate(),
                    block.getDayLabel(),
                    block.getDetailSummary(),
                    block.getMemo(),
                    toInstant(block.getCreatedAt(), sourceZone),
                    toInstant(block.getUpdatedAt(), sourceZone),
                    toInstant(block.getDeletedAt(), sourceZone),
                    previewKeys.get(block.getTripAttachmentId())
            ));

            nextBlockId = Math.max(nextBlockId, block.getId() + 1);
        }

        return new StoryContentDocument(
                story.getId(),
                story.getMood(),
                story.getStorySummary(),
                selectedPlaceIds,
                documentBlocks,
                nextBlockId
        );
    }

    private Instant toInstant(LocalDateTime value, ZoneId sourceZone) {
        if (value == null) {
            return null;
        }

        return value.atZone(sourceZone).toInstant();
    }
}