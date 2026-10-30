package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryOrAttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.AttachmentCannotBeReusedException;
import com.yeodam.yeodambe.common.exception.InvalidStoryUpdateException;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.story.repository.StoryBlockRepository;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.service.request.StoryUpdateRequest;
import com.yeodam.yeodambe.story.service.response.StoryUpdateResponse;
import jakarta.persistence.EntityManager;
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
import java.time.LocalDateTime;
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
    private final StoryBlockRepository storyBlockRepository;
    private final TripDetailPlaceRepository tripDetailPlaceRepository;
    private final StoryValidator storyValidator;
    private final StoryReadService storyReadService;
    private final EntityManager entityManager;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StoryUpdateResponse update(
            Long userId,
            Long storyId,
            StoryUpdateRequest request
    ) {
        storyValidator.validateUpdateBlocks(request.blocks());

        Story story = requireEditableStory(userId, storyId);

        List<StoryBlock> existingBlocks =
                requireExistingBlocks(storyId, request.blocks());

        validateAttachmentReuse(request.blocks(), existingBlocks);

        Map<Long, TripAttachment> attachmentsById =
                requireAttachments(story.getTripId(), request.blocks());

        Map<Long, TripDetailPlace> placesById =
                findPlaces(attachmentsById);

        story.updateContent(request.tone(), request.storySummary());

        applyBlocks(
                storyId,
                request.blocks(),
                existingBlocks,
                attachmentsById,
                placesById
        );

        Long tripId = story.getTripId();
        entityManager.flush();
        entityManager.clear();

        return StoryUpdateResponse.from(
                storyReadService.findStory(userId, tripId)
        );
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
    private List<StoryBlock> requireExistingBlocks(
            Long storyId,
            List<StoryUpdateRequest.Block> requestedBlocks
    ) {
        List<StoryBlock> existingBlocks =
                storyBlockRepository.findAllByStoryId(storyId);

        Map<Long, StoryBlock> blocksById = existingBlocks.stream()
                .collect(Collectors.toMap(
                        StoryBlock::getId,
                        block -> block
                ));

        for (StoryUpdateRequest.Block requested : requestedBlocks) {
            Long blockId = requested.storyBlockId();

            if (blockId != null && !blocksById.containsKey(blockId)) {
                throw new StoryOrAttachmentNotFoundException();
            }
        }

        return existingBlocks;
    }
    private void validateAttachmentReuse(
            List<StoryUpdateRequest.Block> requestedBlocks,
            List<StoryBlock> existingBlocks
    ) {
        Map<Long, StoryBlock> blocksByAttachmentId =
                existingBlocks.stream()
                        .collect(Collectors.toMap(
                                StoryBlock::getTripAttachmentId,
                                block -> block
                        ));

        for (StoryUpdateRequest.Block requested : requestedBlocks) {
            StoryBlock existing =
                    blocksByAttachmentId.get(requested.tripAttachmentId());

            if (requested.storyBlockId() != null
                    && existing != null
                    && !requested.storyBlockId().equals(existing.getId())) {
                throw new AttachmentCannotBeReusedException();
            }
        }
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
    private StoryBlock applyBlock(
            Long storyId,
            StoryUpdateRequest.Block requested,
            StoryBlock existing,
            TripAttachment attachment,
            Map<Long, TripDetailPlace> placesById
    ) {
        boolean photoChanged = existing == null
                || !existing.getTripAttachmentId().equals(attachment.getId());

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

            if (existing == null) {
                return new StoryBlock(
                        storyId,
                        attachment.getId(),
                        place.getId(),
                        place.getStartedAt().toLocalDate(),
                        requested.orderNumber(),
                        requested.dayLabel(),
                        requested.detailSummary(),
                        requested.memo()
                );
            }

            existing.replaceAttachment(
                    attachment.getId(),
                    place.getId(),
                    place.getStartedAt().toLocalDate()
            );
        }

        existing.updateContent(
                requested.dayLabel(),
                requested.detailSummary(),
                requested.memo(),
                requested.orderNumber()
        );
        existing.restore();

        return existing;
    }
    private void applyBlocks(
            Long storyId,
            List<StoryUpdateRequest.Block> requestedBlocks,
            List<StoryBlock> existingBlocks,
            Map<Long, TripAttachment> attachmentsById,
            Map<Long, TripDetailPlace> placesById
    ) {
        Map<Long, StoryBlock> blocksById = existingBlocks.stream()
                .collect(Collectors.toMap(
                        StoryBlock::getId,
                        block -> block
                ));

        Map<Long, StoryBlock> blocksByAttachmentId =
                existingBlocks.stream()
                        .collect(Collectors.toMap(
                                StoryBlock::getTripAttachmentId,
                                block -> block
                        ));

        Set<StoryBlock> retainedBlocks = new HashSet<>();

        for (StoryUpdateRequest.Block requested : requestedBlocks) {
            StoryBlock existing = requested.storyBlockId() == null
                    ? blocksByAttachmentId.get(requested.tripAttachmentId())
                    : blocksById.get(requested.storyBlockId());

            if (existing != null && !retainedBlocks.add(existing)) {
                throw new AttachmentCannotBeReusedException();
            }

            StoryBlock applied = applyBlock(
                    storyId,
                    requested,
                    existing,
                    attachmentsById.get(requested.tripAttachmentId()),
                    placesById
            );

            if (existing == null) {
                storyBlockRepository.save(applied);
            }
        }

        LocalDateTime deletedAt = LocalDateTime.now();
        for (StoryBlock existing : existingBlocks) {
            if (existing.getDeletedAt() == null
                    && !retainedBlocks.contains(existing)) {
                existing.softDelete(deletedAt);
            }
        }
    }
}
