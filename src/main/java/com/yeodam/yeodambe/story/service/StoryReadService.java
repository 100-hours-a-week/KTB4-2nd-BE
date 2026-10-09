package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.common.exception.StoryNotFoundException;
import com.yeodam.yeodambe.story.repository.StoryBlockRepository;
import com.yeodam.yeodambe.story.repository.StoryBlockRepository.BlockDetail;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.service.response.StoryDetailResponse;
import com.yeodam.yeodambe.story.service.response.StoryDetailResponse.Block;
import com.yeodam.yeodambe.story.service.response.StoryDetailResponse.Day;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.service.TripAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoryReadService {
    private final TripAccessService tripAccessService;
    private final StoryRepository storyRepository;
    private final StoryBlockRepository storyBlockRepository;
    private final StoryValidator storyValidator;
    private final TripAttachmentStorageClient tripAttachmentStorageClient;

    public StoryDetailResponse findStory(Long userId, Long tripId) {
        requireAccess(tripId, userId);

        Story story = storyRepository.findCurrentCompletedByTripId(tripId)
                .orElseThrow(StoryNotFoundException::new);
        List<BlockDetail> blocks = storyBlockRepository.findDetailsByStoryId(story.getId());

        storyValidator.validateBlockCount(blocks);
        storyValidator.validateBlockRelations(tripId, blocks);

        List<BlockDetail> visibleBlocks = blocks.stream()
                .filter(block -> !isDeletedPhoto(block))
                .toList();
        storyValidator.validateDisplayBlocks(tripId, visibleBlocks);

        return new StoryDetailResponse(
                story.getId(),
                tripId,
                true,
                story.getMood(),
                story.getStorySummary(),
                buildDays(visibleBlocks)
        );
    }

    private void requireAccess(Long tripId, Long userId) {
        try {
            tripAccessService.requireReadableTrip(tripId, userId);
        } catch (TripNotFoundException e) {
            throw new StoryNotFoundException();
        }
    }

    private boolean isDeletedPhoto(BlockDetail detail) {
        return detail.getAttachment().getDeletedAt() != null || detail.getFile().getDeletedAt() != null;
    }

    private List<Day> buildDays(List<BlockDetail> details) {
        List<Day> days = new ArrayList<>();
        for (BlockDetail detail : details) {
            LocalDate date = detail.getPlace().getStartedAt().toLocalDate();
            String dayLabel = detail.getBlock().getDayLabel();

            if (days.isEmpty()
                    || !date.equals(days.getLast().date())
                    || !dayLabel.equals(days.getLast().dayLabel())) {
                days.add(new Day(date, dayLabel, new ArrayList<>()));
            }

            days.getLast().blocks().add(toBlockResponse(detail));
        }
        return days.stream()
                .map(day -> new Day(day.date(), day.dayLabel(), List.copyOf(day.blocks())))
                .toList();
    }

    private Block toBlockResponse(BlockDetail detail) {
        StoryBlock block = detail.getBlock();
        String thumbnailUrl = tripAttachmentStorageClient.createReadUrl(
                detail.getAttachment().getPreviewStorageKey()
        );
        return new Block(
                block.getId(),
                block.getOrderNumber(),
                detail.getAttachment().getTripPlaceId(),
                detail.getAttachment().getId(),
                thumbnailUrl,
                block.getDetailSummary(),
                block.getMemo() == null ? "" : block.getMemo()
        );
    }
}
