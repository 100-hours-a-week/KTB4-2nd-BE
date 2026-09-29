package com.yeodam.yeodambe.trip.service;


import tools.jackson.databind.ObjectMapper;
import com.yeodam.yeodambe.common.exception.PlaceFolderNotFoundException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.yeodam.yeodambe.trip.service.request.AttachmentCursor;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TripAttachmentListService {

    private static final int PAGE_SIZE = 18;
    private static final int QUERY_SIZE = PAGE_SIZE + 1;

    private final TripRepository tripRepository;
    private final TripDetailPlaceRepository tripDetailPlaceRepository;
    private final TripAttachmentRepository tripAttachmentRepository;
    private final TripAttachmentStorageClient tripAttachmentStorageClient;
    private final ObjectMapper objectMapper;

    public TripAttachmentListResponse findByPlaceFolder(
            Long userId,
            Long tripId,
            Long tripPlaceId,
            String cursor
    ) {
        if (!tripRepository.existsByIdAndUserIdAndDeletedAtIsNull(
                tripId,
                userId
        ) || !tripDetailPlaceRepository.existsByIdAndTripIdAndDeletedAtIsNull(
                tripPlaceId,
                tripId
        )) {
            throw new PlaceFolderNotFoundException();
        }

        AttachmentCursor attachmentCursor = AttachmentCursor.decode(
                cursor,
                objectMapper
        );

        List<TripAttachment> attachments =
                tripAttachmentRepository.findByPlaceFolderWithCursor(
                        tripId,
                        tripPlaceId,
                        ClassificationStatus.ACTIVE,
                        attachmentCursor == null
                                ? null
                                : attachmentCursor.createdAt(),
                        attachmentCursor == null
                                ? null
                                : attachmentCursor.tripAttachmentId(),
                        PageRequest.of(0, QUERY_SIZE)
                );

        boolean hasNext = attachments.size() > PAGE_SIZE;

        List<TripAttachmentListResponse.Item> items = attachments.stream()
                .limit(PAGE_SIZE)
                .map(attachment -> new TripAttachmentListResponse.Item(
                        attachment.getId(),
                        tripAttachmentStorageClient.createReadUrl(
                                attachment.getPreviewStorageKey()
                        )
                ))
                .toList();

        String nextCursor = null;

        if (hasNext) {
            TripAttachment lastAttachment = attachments.get(PAGE_SIZE - 1);
            nextCursor = new AttachmentCursor(
                    lastAttachment.getCreatedAt(),
                    lastAttachment.getId()
            ).encode(objectMapper);
        }

        return new TripAttachmentListResponse(
                items,
                hasNext,
                nextCursor
        );
    }
}
