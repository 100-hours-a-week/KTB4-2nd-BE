package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.RunpodStatusUnavailableException;
import com.yeodam.yeodambe.common.exception.StoryBlockNotFoundException;
import com.yeodam.yeodambe.common.exception.StorySimilarAttachmentUnavailableException;
import com.yeodam.yeodambe.integration.service.RunpodStatusService;
import com.yeodam.yeodambe.story.client.StorySimilarAttachmentAiClient;
import com.yeodam.yeodambe.story.client.request.StorySimilarAttachmentAiRequest;
import com.yeodam.yeodambe.story.client.response.StorySimilarAttachmentAiResponse;
import com.yeodam.yeodambe.story.exception.StorySimilarAttachmentAiRequestException;
import com.yeodam.yeodambe.story.exception.StorySimilarAttachmentInternalErrorMessage;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.service.response.StorySimilarAttachmentListResponse;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StorySimilarAttachmentService {
    private static final int RESULT_LIMIT = 10;

    private final StoryRepository storyRepository;
    private final StoryContentRepository storyContentRepository;
    private final TripAttachmentRepository tripAttachmentRepository;
    private final StorySimilarAttachmentAiClient aiClient;
    private final RunpodStatusService runpodStatusService;

    public StorySimilarAttachmentListResponse findSimilar(
            Long userId,
            Long storyId,
            Long storyBlockId
    ) {
        var story = storyRepository.findOwnedCurrentCompletedForSimilarById(
                storyId, userId
        ).orElseThrow(StoryBlockNotFoundException::new);

        var content = storyContentRepository.findById(story.getId())
                .orElseThrow(() -> new StoryDataIntegrityException(
                        StoryGenerationInternalErrorMessage.CONTENT_DOCUMENT_MISSING
                ));

        var blocks = content.getBlocks().stream()
                .filter(block -> block.deletedAt() == null)
                .toList();

        var block = blocks.stream()
                .filter(candidate -> storyBlockId.equals(candidate.id()))
                .findFirst()
                .orElseThrow(StoryBlockNotFoundException::new);

        TripAttachment source = tripAttachmentRepository
                .findAllActiveWithTripAndFileByIds(
                        List.of(block.tripAttachmentId())
                )
                .stream()
                .findFirst()
                .orElseThrow(StoryBlockNotFoundException::new);

        var place = source.getTripPlace();

        if (!story.getTripId().equals(source.getTripId())
                || source.getClassificationStatus() != ClassificationStatus.ACTIVE
                || place == null
                || place.getDeletedAt() != null
                || !story.getTripId().equals(place.getTripId())) {
            throw new StoryBlockNotFoundException();
        }

        Set<Long> usedAttachmentIds = blocks.stream()
                .map(existing -> existing.tripAttachmentId())
                .collect(Collectors.toSet());

        List<Long> candidateIds = tripAttachmentRepository
                .findAllActiveByTripPlaceId(
                        source.getTripPlaceId(), ClassificationStatus.ACTIVE
                )
                .stream()
                .filter(attachment ->
                        source.getTripId().equals(attachment.getTripId()))
                .filter(attachment ->
                        !usedAttachmentIds.contains(attachment.getId()))
                .map(TripAttachment::getId)
                .toList();

        if (candidateIds.isEmpty()) {
            return new StorySimilarAttachmentListResponse(
                    source.getId(), List.of()
            );
        }

        requireAiReady();
        var request = new StorySimilarAttachmentAiRequest(
                source.getId(), candidateIds, RESULT_LIMIT
        );
        var response = aiClient.findSimilar(source.getTripId(), request);

        validateResponse(source.getId(), candidateIds, response);

        return new StorySimilarAttachmentListResponse(
                source.getId(),
                response.items().stream()
                        .map(item -> new StorySimilarAttachmentListResponse.Item(
                                item.tripAttachmentId(), item.score()
                        ))
                        .toList()
        );
    }

    private void requireAiReady() {
        try {
            if (!runpodStatusService.getStatus().ready()) {
                throw new StorySimilarAttachmentUnavailableException();
            }
        } catch (RunpodStatusUnavailableException exception) {
            throw new StorySimilarAttachmentUnavailableException();
        }
    }

    private void validateResponse(
            Long sourceAttachmentId,
            List<Long> candidateIds,
            StorySimilarAttachmentAiResponse response
    ) {
        if (!sourceAttachmentId.equals(response.sourceAttachmentId())
                || response.items() == null
                || response.items().size() > RESULT_LIMIT) {
            throw new StorySimilarAttachmentAiRequestException(
                    StorySimilarAttachmentInternalErrorMessage.AI_RESPONSE_INVALID
            );
        }

        Set<Long> allowedIds = new HashSet<>(candidateIds);
        Set<Long> receivedIds = new HashSet<>();

        for (var item : response.items()) {
            if (item == null
                    || item.tripAttachmentId() == null
                    || item.score() == null
                    || !Double.isFinite(item.score())
                    || !allowedIds.contains(item.tripAttachmentId())
                    || !receivedIds.add(item.tripAttachmentId())) {
                throw new StorySimilarAttachmentAiRequestException(
                        StorySimilarAttachmentInternalErrorMessage.AI_RESPONSE_INVALID
                );
            }
        }
    }
}
