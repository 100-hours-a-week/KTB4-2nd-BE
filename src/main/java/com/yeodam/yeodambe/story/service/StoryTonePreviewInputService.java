package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryNotFoundException;
import com.yeodam.yeodambe.common.exception.StoryOrAttachmentNotFoundException;
import com.yeodam.yeodambe.story.client.request.StoryTonePreviewAiRequest;
import com.yeodam.yeodambe.story.repository.StoryBlockRepository;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.service.request.StoryTonePreviewRequest;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;

import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StoryTonePreviewInputService {
    private final StoryRepository storyRepository;
    private final StoryBlockRepository storyBlockRepository;
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

        var existingBlocks =
                storyBlockRepository.findAllByStoryId(storyId);

        storyValidator.validatePreviewBlockIds(
                request.blocks(), existingBlocks
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

        return new PreparedRequest(story.getTripId(), aiRequest);
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

    public record PreparedRequest(
            Long tripId,
            StoryTonePreviewAiRequest request
    ) {
    }
}