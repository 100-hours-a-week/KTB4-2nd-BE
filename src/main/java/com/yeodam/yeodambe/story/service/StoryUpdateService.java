package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryOrAttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.AttachmentCannotBeReusedException;
import com.yeodam.yeodambe.common.exception.InvalidStoryUpdateException;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.service.request.StoryUpdateRequest;
import com.yeodam.yeodambe.story.service.response.StoryUpdateResponse;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class StoryUpdateService {
    private final StoryRepository storyRepository;
    private final TripRepository tripRepository;
    private final TripAttachmentRepository tripAttachmentRepository;
    private final TripDetailPlaceRepository tripDetailPlaceRepository;
    private final StoryValidator storyValidator;
    private final StoryReadService storyReadService;
    private final StoryContentRepository storyContentRepository;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StoryUpdateResponse update(
            Long userId,
            Long storyId,
            StoryUpdateRequest request
    ) {
        storyValidator.validateUpdateBlocks(request.blocks());
        if (request.tone() == null || request.storySummary() == null
                || !request.isStorySummaryValid()) {
            throw new InvalidStoryUpdateException();
        }
        for (StoryUpdateRequest.Block block : request.blocks()) {
            if (!block.isBlockTextValid() || !block.isMemoLengthValid()) {
                throw new InvalidStoryUpdateException();
            }
        }

        Story story = requireEditableStory(userId, storyId);
        StoryContentDocument content = requireContent(storyId);
        validateContentBlockIds(content, request.blocks());
        validateContentAttachmentReuse(request.blocks(), content.getBlocks());

        Map<Long, TripAttachment> attachmentsById =
                requireAttachments(story.getTripId(), request.blocks());
        Map<Long, TripDetailPlace> placesById = findPlaces(attachmentsById);
        List<StoryContentBlock> updatedBlocks = applyContentBlocks(
                content, request.blocks(), attachmentsById, placesById);
        content.updateContent(request.tone(), request.storySummary(), updatedBlocks);

        StoryUpdateResponse response = StoryUpdateResponse.from(
                storyReadService.prepareContentResponse(story.getTripId(), true, content));
        storyContentRepository.save(content);
        return response;
    }

    private StoryContentDocument requireContent(Long storyId) {
        return storyContentRepository.findById(storyId)
                .orElseThrow(() -> new StoryDataIntegrityException(
                        StoryGenerationInternalErrorMessage.CONTENT_DOCUMENT_MISSING
                ));
    }

    private void validateContentBlockIds(
            StoryContentDocument content,
            List<StoryUpdateRequest.Block> requestedBlocks
    ) {
        Set<Long> existingBlockIds = new HashSet<>();

        for (StoryContentBlock block : content.getBlocks()) {
            existingBlockIds.add(block.id());
        }

        for (StoryUpdateRequest.Block requested : requestedBlocks) {
            Long blockId = requested.storyBlockId();

            if (blockId != null && !existingBlockIds.contains(blockId)) {
                throw new StoryOrAttachmentNotFoundException();
            }
        }
    }

    private void validateContentAttachmentReuse(
            List<StoryUpdateRequest.Block> requestedBlocks,
            List<StoryContentBlock> existingBlocks
    ) {
        Map<Long, StoryContentBlock> blocksByAttachmentId =
                new java.util.HashMap<>();

        for (StoryContentBlock block : existingBlocks) {
            blocksByAttachmentId.put(block.tripAttachmentId(), block);
        }

        for (StoryUpdateRequest.Block requested : requestedBlocks) {
            StoryContentBlock existing =
                    blocksByAttachmentId.get(requested.tripAttachmentId());

            if (requested.storyBlockId() != null
                    && existing != null
                    && !requested.storyBlockId().equals(existing.id())) {
                throw new AttachmentCannotBeReusedException();
            }
        }
    }

    private StoryContentBlock applyContentBlock(
            StoryContentDocument content,
            StoryUpdateRequest.Block requested,
            StoryContentBlock existing,
            TripAttachment attachment,
            Map<Long, TripDetailPlace> placesById,
            Instant now
    ) {
        boolean photoChanged = existing == null
                || !existing.tripAttachmentId().equals(attachment.getId());

        Long sourcePlaceId = existing == null
                ? null : existing.sourceTripPlaceId();
        LocalDate sourceDate = existing == null
                ? null : existing.sourceDate();

        if (photoChanged) {
            Long placeId = attachment.getTripPlaceId();
            TripDetailPlace place = placeId == null
                    ? null : placesById.get(placeId);

            if (place == null
                    || place.getDeletedAt() != null
                    || !attachment.getTripId().equals(place.getTripId())
                    || place.getStartedAt() == null) {
                throw new InvalidStoryUpdateException();
            }

            sourcePlaceId = place.getId();
            sourceDate = place.getStartedAt().toLocalDate();
        }

        Long blockId = existing == null
                ? content.issueBlockId() : existing.id();
        Instant createdAt = existing == null
                ? now : existing.createdAt();

        return new StoryContentBlock(
                blockId,
                attachment.getId(),
                sourcePlaceId,
                sourceDate,
                requested.dayLabel(),
                requested.detailSummary(),
                requested.memo(),
                createdAt,
                now,
                null,
                attachment.getPreviewStorageKey()
        );
    }

    private List<StoryContentBlock> applyContentBlocks(
            StoryContentDocument content,
            List<StoryUpdateRequest.Block> requestedBlocks,
            Map<Long, TripAttachment> attachmentsById,
            Map<Long, TripDetailPlace> placesById
    ) {
        Map<Long, StoryContentBlock> blocksById = new java.util.HashMap<>();
        Map<Long, StoryContentBlock> blocksByAttachmentId =
                new java.util.HashMap<>();

        for (StoryContentBlock block : content.getBlocks()) {
            blocksById.put(block.id(), block);
            blocksByAttachmentId.put(block.tripAttachmentId(), block);
        }

        List<StoryUpdateRequest.Block> orderedRequests =
                new ArrayList<>(requestedBlocks);
        orderedRequests.sort(Comparator.comparingInt(
                block -> block.orderNumber()
        ));

        Set<Long> retainedBlockIds = new HashSet<>();
        List<StoryContentBlock> result = new ArrayList<>();
        Instant now = Instant.now();

        for (StoryUpdateRequest.Block requested : orderedRequests) {
            StoryContentBlock existing = requested.storyBlockId() == null
                    ? blocksByAttachmentId.get(requested.tripAttachmentId())
                    : blocksById.get(requested.storyBlockId());

            if (existing != null && !retainedBlockIds.add(existing.id())) {
                throw new AttachmentCannotBeReusedException();
            }

            result.add(applyContentBlock(
                    content, requested, existing,
                    attachmentsById.get(requested.tripAttachmentId()),
                    placesById, now
            ));
        }

        for (StoryContentBlock existing : content.getBlocks()) {
            if (retainedBlockIds.contains(existing.id())) {
                continue;
            }
            if (existing.deletedAt() != null) {
                result.add(existing);
                continue;
            }
            result.add(new StoryContentBlock(
                    existing.id(), existing.tripAttachmentId(),
                    existing.sourceTripPlaceId(), existing.sourceDate(),
                    existing.dayLabel(), existing.detailSummary(), existing.memo(),
                    existing.createdAt(), now, now,
                    existing.previewStorageKey()
            ));
        }

        return result;
    }

    private Story requireEditableStory(Long userId, Long storyId) {
        Long tripId = storyRepository.findTripIdByStoryId(storyId)
                .orElseThrow(StoryOrAttachmentNotFoundException::new);

        tripRepository.findOwnedActiveForUpdate(tripId, userId)
                .orElseThrow(StoryOrAttachmentNotFoundException::new);

        return storyRepository
                .findOwnedCurrentCompletedById(storyId, userId)
                .orElseThrow(StoryOrAttachmentNotFoundException::new);
    }
    private Map<Long, TripAttachment> requireAttachments(
            Long tripId,
            List<StoryUpdateRequest.Block> blocks
    ) {
        List<Long> attachmentIds = blocks.stream()
                .map(StoryUpdateRequest.Block::tripAttachmentId)
                .toList();

        List<TripAttachment> attachments =
                tripAttachmentRepository
                        .findAllActiveWithTripAndFileByIds(attachmentIds);

        if (attachments.size() != attachmentIds.size()
                || attachments.stream().anyMatch(
                        attachment -> !tripId.equals(attachment.getTripId())
                )) {
            throw new StoryOrAttachmentNotFoundException();
        }

        return attachments.stream()
                .collect(Collectors.toMap(
                        TripAttachment::getId,
                        attachment -> attachment
                ));
    }
    private Map<Long, TripDetailPlace> findPlaces(
            Map<Long, TripAttachment> attachmentsById
    ) {
        List<Long> placeIds = attachmentsById.values().stream()
                .map(TripAttachment::getTripPlaceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (placeIds.isEmpty()) {
            return Map.of();
        }

        return tripDetailPlaceRepository.findAllById(placeIds).stream()
                .collect(Collectors.toMap(
                        TripDetailPlace::getId,
                        place -> place
                ));
    }
}
