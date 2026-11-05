package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryNotFoundException;
import com.yeodam.yeodambe.common.exception.StoryOrAttachmentNotFoundException;
import com.yeodam.yeodambe.story.client.request.StoryTonePreviewAiRequest;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.service.request.StoryTonePreviewRequest;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StoryTonePreviewInputService {
    private final StoryRepository storyRepository;
    private final StoryContentRepository storyContentRepository;
    private final TripAttachmentRepository tripAttachmentRepository;
    private final StoryTonePreviewRequestValidator requestValidator;
    private final StoryValidator storyValidator;

    @Transactional(readOnly = true)
    public PreparedRequest prepare(
            Long userId,
            Long storyId,
            StoryTonePreviewRequest request
    ) {
        requestValidator.validate(request);

        var story = storyRepository
                .findOwnedCurrentCompletedForPreviewById(storyId, userId)
                .orElseThrow(StoryNotFoundException::new);

        var content = storyContentRepository.findById(story.getId())
                .orElseThrow(() -> new StoryDataIntegrityException(
                        StoryGenerationInternalErrorMessage.CONTENT_DOCUMENT_MISSING
                ));

        var existingBlocks = content.getBlocks().stream()
                .filter(block -> block.deletedAt() == null)
                .toList();

        var existingBlockIds = existingBlocks.stream()
                .map(block -> block.id())
                .collect(Collectors.toSet());

        storyValidator.validatePreviewBlockIds(
                request.blocks(), existingBlockIds
        );

        var attachmentIds = request.blocks().stream()
                .map(StoryTonePreviewRequest.Block::tripAttachmentId)
                .toList();

        var attachments = tripAttachmentRepository
                .findAllActiveWithTripAndFileByIds(attachmentIds);

        storyValidator.validatePreviewAttachments(
                story.getTripId(), attachmentIds.size(), attachments
        );

        Map<Long, TripAttachment> attachmentsById = attachments.stream()
                .collect(Collectors.toMap(
                        TripAttachment::getId,
                        Function.identity()
                ));

        var blocks = request.blocks().stream()
                .sorted(Comparator.comparing(
                        StoryTonePreviewRequest.Block::orderNumber
                ))
                .map(block -> assembleBlock(
                        block,
                        attachmentsById.get(block.tripAttachmentId())
                ))
                .toList();

        var trip = story.getTrip();

        var aiRequest = new StoryTonePreviewAiRequest(
                trip.getTripName(),
                new StoryTonePreviewAiRequest.Period(
                        trip.getStartDate(),
                        trip.getEndDate()
                ),
                request.tone(),
                blocks
        );

        List<BlockSource> sources = new ArrayList<>();

        for (StoryTonePreviewAiRequest.Block block : blocks) {
            StoryTonePreviewRequest.Block requested = null;

            for (StoryTonePreviewRequest.Block candidate : request.blocks()) {
                if (candidate.tripAttachmentId().equals(block.tripAttachmentId())) {
                    requested = candidate;
                    break;
                }
            }

            TripAttachment attachment =
                    attachmentsById.get(block.tripAttachmentId());

            StoryContentBlock existing = findExistingBlock(
                    requested, existingBlocks
            );

            sources.add(assembleSource(requested, attachment, existing));
        }

        return new PreparedRequest(
                story.getTripId(),
                aiRequest,
                List.copyOf(sources)
        );
    }

    private StoryTonePreviewAiRequest.Block assembleBlock(
            StoryTonePreviewRequest.Block requested,
            TripAttachment attachment
    ) {
        var place = attachment.getTripPlace();

        if (place == null
                || place.getDeletedAt() != null
                || !attachment.getTripId().equals(place.getTripId())) {
            throw new StoryOrAttachmentNotFoundException();
        }

        String memo = requested.memo();
        if (memo != null && memo.isEmpty()) {
            memo = null;
        }

        return new StoryTonePreviewAiRequest.Block(
                requested.orderNumber(),
                attachment.getId(),
                place.getPlaceName(),
                attachment.getTakenAt().atOffset(ZoneOffset.ofHours(9)),
                attachment.getAnalyzeStorageKey(),
                requested.dayLabel(),
                requested.detailSummary(),
                memo
        );
    }

    private StoryContentBlock findExistingBlock(
            StoryTonePreviewRequest.Block requested,
            List<StoryContentBlock> existingBlocks
    ) {
        for (StoryContentBlock existing : existingBlocks) {
            if (requested.storyBlockId() != null) {
                if (requested.storyBlockId().equals(existing.id())) {
                    return existing;
                }
            } else if (requested.tripAttachmentId()
                    .equals(existing.tripAttachmentId())) {
                return existing;
            }
        }

        return null;
    }

    private BlockSource assembleSource(
            StoryTonePreviewRequest.Block requested,
            TripAttachment attachment,
            StoryContentBlock existing
    ) {
        boolean samePhoto = existing != null
                && existing.tripAttachmentId().equals(attachment.getId());

        Long placeId;
        LocalDate date;

        if (samePhoto) {
            placeId = existing.sourceTripPlaceId();
            date = existing.sourceDate();
        } else {
            if (attachment.getTripPlace().getStartedAt() == null) {
                throw new StoryOrAttachmentNotFoundException();
            }

            placeId = attachment.getTripPlace().getId();
            date = attachment.getTripPlace().getStartedAt().toLocalDate();
        }

        return new BlockSource(
                existing == null ? null : existing.id(),
                requested.orderNumber(),
                attachment.getId(),
                placeId,
                date,
                attachment.getPreviewStorageKey()
        );
    }

    public record PreparedRequest(
            Long tripId,
            StoryTonePreviewAiRequest request,
            List<BlockSource> sources
    ) {
    }

    public record BlockSource(
            Long storyBlockId,
            Integer orderNumber,
            Long tripAttachmentId,
            Long tripPlaceId,
            LocalDate date,
            String previewStorageKey
    ) {
    }
}