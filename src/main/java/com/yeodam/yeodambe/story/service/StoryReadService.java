package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.story.service.response.StoryReadContext;
import com.yeodam.yeodambe.story.service.response.StoryReadContent;
import com.yeodam.yeodambe.common.exception.StoryNotFoundException;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.service.response.StoryDetailResponse;
import com.yeodam.yeodambe.story.service.response.StoryDetailResponse.Block;
import com.yeodam.yeodambe.story.service.response.StoryDetailResponse.Day;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.service.TripEditAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.service.response.StoryContentBlockDetail;
import com.yeodam.yeodambe.story.repository.StoryBlockReferenceQuery;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoryReadService {
    private final TripEditAuthorizationService authorizationService;
    private final StoryRepository storyRepository;
    private final StoryValidator storyValidator;
    private final TripAttachmentStorageClient tripAttachmentStorageClient;
    private final StoryContentRepository storyContentRepository;
    private final StoryBlockReferenceQuery referenceQuery;

    public StoryDetailResponse findStory(Long userId, Long tripId) {
        StoryReadContext context = storyRepository.findReadContextByTripId(tripId)
                .orElseThrow(StoryNotFoundException::new);
        requireAccess(context, userId);
        if (context.storyId() == null) {
            throw new StoryNotFoundException();
        }
        StoryReadContent content = storyContentRepository.findActiveContentById(context.storyId())
                .orElseThrow(() -> new StoryDataIntegrityException(
                        StoryGenerationInternalErrorMessage.CONTENT_DOCUMENT_MISSING
                ));
        return prepareContentResponse(tripId, context.ownerId().equals(userId), content);
    }

    StoryDetailResponse prepareContentResponse(
            Long tripId,
            boolean userByMe,
            StoryContentDocument content
    ) {
        return prepareContentResponse(tripId, userByMe, new StoryReadContent(
                content.getId(), content.getMood(), content.getStorySummary(), content.getBlocks()
        ));
    }

    private StoryDetailResponse prepareContentResponse(
            Long tripId,
            boolean userByMe,
            StoryReadContent content
    ) {
        List<StoryContentBlock> blocks = content.blocks().stream()
                .filter(block -> block.deletedAt() == null)
                .toList();

        storyValidator.validateBlockCount(blocks);

        List<StoryContentBlockDetail> details =
                loadContentBlockDetails(blocks);

        storyValidator.validateContentBlockRelations(tripId, details);

        List<StoryContentBlockDetail> visibleBlocks = details.stream()
                .filter(detail -> !isDeletedContentPhoto(detail))
                .toList();

        storyValidator.validateContentDisplayBlocks(tripId, visibleBlocks);

        return new StoryDetailResponse(
                content.id(),
                tripId,
                userByMe,
                content.mood(),
                content.storySummary(),
                buildContentDays(details)
        );
    }

    private List<StoryContentBlockDetail> loadContentBlockDetails(
            List<StoryContentBlock> blocks
    ) {
        if (blocks.isEmpty()) {
            return List.of();
        }
        return referenceQuery.findByBlocks(blocks);
    }

    private void requireAccess(StoryReadContext context, Long userId) {
        try {
            authorizationService.requireReadPermission(context.tripId(), context.ownerId(), userId);
        } catch (TripNotFoundException exception) {
            throw new StoryNotFoundException();
        }
    }

    private boolean isDeletedContentPhoto(StoryContentBlockDetail detail) {
        return detail.attachmentDeletedAt() != null
                || detail.fileDeletedAt() != null;
    }

    private List<Day> buildContentDays(
            List<StoryContentBlockDetail> details
    ) {
        List<Day> days = new ArrayList<>();

        for (int index = 0; index < details.size(); index++) {
            StoryContentBlockDetail detail = details.get(index);

            if (isDeletedContentPhoto(detail)) {
                continue;
            }

            LocalDate date = detail.block().sourceDate();
            String dayLabel = detail.block().dayLabel();

            if (days.isEmpty()
                    || !date.equals(days.getLast().date())
                    || !dayLabel.equals(days.getLast().dayLabel())) {
                days.add(new Day(date, dayLabel, new ArrayList<>()));
            }

            days.getLast().blocks().add(
                    toContentBlockResponse(detail, index + 1)
            );
        }

        return days.stream()
                .map(day -> new Day(
                        day.date(),
                        day.dayLabel(),
                        List.copyOf(day.blocks())
                ))
                .toList();
    }

    private Block toContentBlockResponse(
            StoryContentBlockDetail detail,
            int orderNumber
    ) {
        StoryContentBlock block = detail.block();

        String thumbnailUrl = tripAttachmentStorageClient.createReadUrl(
                detail.currentPreviewStorageKey()
        );

        return new Block(
                block.id(),
                orderNumber,
                block.sourceTripPlaceId(),
                detail.attachmentId(),
                thumbnailUrl,
                block.detailSummary(),
                block.memo() == null ? "" : block.memo()
        );
    }

}
