package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.story.entity.StorySelectedPlace;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
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

    public StoryContentDocument createFromResult(
            Story story,
            StoryGenerationAiResponse.Result result,
            List<Long> selectedPlaceIds,
            Map<Long, LocalDate> sourceDates,
            Map<Long, String> previewKeys,
            String initialContentHash
    ) {
        List<StoryContentBlock> blocks = new ArrayList<>();
        Instant createdAt = toInstant(story.getCreatedAt(), ZoneOffset.UTC);
        long nextBlockId = 1;
        List<StoryGenerationAiResponse.Block> orderedBlocks = result.blocks().stream()
                .sorted(Comparator.comparing(StoryGenerationAiResponse.Block::orderNumber))
                .toList();
        for (StoryGenerationAiResponse.Block block : orderedBlocks) {
            blocks.add(new StoryContentBlock(
                    nextBlockId++, block.tripAttachmentId(), block.tripPlaceId(),
                    sourceDates.get(block.tripPlaceId()), block.dayLabel(), block.detailSummary(),
                    block.memo(), createdAt, createdAt, null, previewKeys.get(block.tripAttachmentId())
            ));
        }
        return new StoryContentDocument(
                story.getId(), story.getMood(), result.storySummary(), selectedPlaceIds,
                blocks, nextBlockId, initialContentHash
        );
    }

    private Instant toInstant(LocalDateTime value, ZoneId sourceZone) {
        if (value == null) {
            return null;
        }

        return value.atZone(sourceZone).toInstant();
    }
}