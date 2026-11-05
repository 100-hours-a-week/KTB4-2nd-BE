package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.common.exception.StoryNotFoundException;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.service.response.StoryDetailResponse;
import com.yeodam.yeodambe.story.service.response.StoryDetailResponse.Block;
import com.yeodam.yeodambe.story.service.response.StoryDetailResponse.Day;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.service.TripAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.service.response.StoryContentBlockDetail;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;

import java.util.HashMap;
import java.util.Map;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoryReadService {
    private final TripAccessService tripAccessService;
    private final StoryRepository storyRepository;
    private final StoryValidator storyValidator;
    private final TripAttachmentStorageClient tripAttachmentStorageClient;
    private final StoryContentRepository storyContentRepository;
    private final TripAttachmentRepository tripAttachmentRepository;
    private final TripDetailPlaceRepository tripDetailPlaceRepository;

    public StoryDetailResponse findStory(Long userId, Long tripId) {
        Trip trip = requireAccess(tripId, userId);

        Story story = storyRepository.findCurrentCompletedByTripId(tripId)
                .orElseThrow(StoryNotFoundException::new);

        var content = storyContentRepository.findById(story.getId())
                .orElseThrow(() -> new StoryDataIntegrityException(
                        StoryGenerationInternalErrorMessage.CONTENT_DOCUMENT_MISSING
                ));

        return prepareContentResponse(tripId, trip.getUserId().equals(userId), content);
    }

    StoryDetailResponse prepareContentResponse(
            Long tripId,
            boolean userByMe,
            StoryContentDocument content
    ) {
        List<StoryContentBlock> blocks = content.getBlocks().stream()
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
                content.getId(),
                tripId,
                userByMe,
                content.getMood(),
                content.getStorySummary(),
                buildContentDays(details)
        );
    }

    private List<StoryContentBlockDetail> loadContentBlockDetails(
            List<StoryContentBlock> blocks
    ) {
        if (blocks.isEmpty()) {
            return List.of();
        }

        List<Long> attachmentIds = new ArrayList<>();
        List<Long> placeIds = new ArrayList<>();

        for (StoryContentBlock block : blocks) {
            attachmentIds.add(block.tripAttachmentId());
            placeIds.add(block.sourceTripPlaceId());
        }

        Map<Long, TripAttachment> attachmentsById = new HashMap<>();

        for (TripAttachment attachment :
                tripAttachmentRepository.findAllWithFileByIds(attachmentIds)) {
            attachmentsById.put(attachment.getId(), attachment);
        }

        Map<Long, TripDetailPlace> placesById = new HashMap<>();

        for (TripDetailPlace place :
                tripDetailPlaceRepository.findAllById(placeIds)) {
            placesById.put(place.getId(), place);
        }

        List<StoryContentBlockDetail> details = new ArrayList<>();

        for (StoryContentBlock block : blocks) {
            TripAttachment attachment =
                    attachmentsById.get(block.tripAttachmentId());

            details.add(new StoryContentBlockDetail(
                    block,
                    attachment,
                    attachment == null ? null : attachment.getFile(),
                    placesById.get(block.sourceTripPlaceId())
            ));
        }

        return details;
    }

    private Trip requireAccess(Long tripId, Long userId) {
        try {
            return tripAccessService.requireReadableTrip(tripId, userId);
        } catch (TripNotFoundException e) {
            throw new StoryNotFoundException();
        }
    }

    private boolean isDeletedContentPhoto(StoryContentBlockDetail detail) {
        return detail.attachment().getDeletedAt() != null
                || detail.file().getDeletedAt() != null;
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
                detail.attachment().getPreviewStorageKey()
        );

        return new Block(
                block.id(),
                orderNumber,
                block.sourceTripPlaceId(),
                detail.attachment().getId(),
                thumbnailUrl,
                block.detailSummary(),
                block.memo() == null ? "" : block.memo()
        );
    }

}
