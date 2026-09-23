package com.yeodam.yeodambe.trip.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.yeodam.yeodambe.common.exception.InvalidCursorException;
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

import java.time.LocalDateTime;
import java.util.Base64;
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

        AttachmentCursor attachmentCursor = decodeCursor(cursor);

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

        String nextCursor = hasNext
                ? encodeCursor(attachments.get(PAGE_SIZE - 1))
                : null;

        return new TripAttachmentListResponse(
                items,
                hasNext,
                nextCursor
        );
    }

    private AttachmentCursor decodeCursor(String cursor) {
        if (cursor == null) {
            return null;
        }

        if (cursor.isBlank()) {
            throw new InvalidCursorException();
        }

        try {
            byte[] decoded = Base64.getUrlDecoder()
                    .decode(cursor);

            AttachmentCursor attachmentCursor = objectMapper.readValue(
                    decoded,
                    AttachmentCursor.class
            );

            if (attachmentCursor.createdAt() == null
                    || attachmentCursor.tripAttachmentId() == null) {
                throw new InvalidCursorException();
            }

            return attachmentCursor;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidCursorException();
        }
    }

    private String encodeCursor(TripAttachment attachment) {
        try {
            byte[] serialized = objectMapper.writeValueAsBytes(
                    new AttachmentCursor(
                            attachment.getCreatedAt(),
                            attachment.getId()
                    )
            );

            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(serialized);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "첨부 목록 커서를 생성할 수 없습니다.",
                    exception
            );
        }
    }

    private record AttachmentCursor(
            LocalDateTime createdAt,
            Long tripAttachmentId
    ) {
    }
}