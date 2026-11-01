package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryBlockNotFoundException;
import com.yeodam.yeodambe.story.client.StorySimilarAttachmentAiClient;
import com.yeodam.yeodambe.story.client.request.StorySimilarAttachmentAiRequest;
import com.yeodam.yeodambe.story.client.response.StorySimilarAttachmentAiResponse;
import com.yeodam.yeodambe.story.exception.StorySimilarAttachmentAiRequestException;
import com.yeodam.yeodambe.story.exception.StorySimilarAttachmentInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryBlockRepository;
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

    private final StoryBlockRepository storyBlockRepository;
    private final TripAttachmentRepository tripAttachmentRepository;
    private final StorySimilarAttachmentAiClient aiClient;

    public StorySimilarAttachmentListResponse findSimilar(
            Long userId,
            Long storyId,
            Long storyBlockId
    ) {
        var block = storyBlockRepository.findOwnedCurrentCompletedBlock(
                storyId, storyBlockId, userId
        ).orElseThrow(StoryBlockNotFoundException::new);

        TripAttachment source = block.getAttachment();

        Set<Long> usedAttachmentIds = storyBlockRepository
                .findDetailsByStoryId(storyId)
                .stream()
                .map(detail -> detail.getBlock().getTripAttachmentId())
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
