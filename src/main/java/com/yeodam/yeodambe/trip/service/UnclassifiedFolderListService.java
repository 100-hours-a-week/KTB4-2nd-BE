package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidAttachmentIssueException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.AttachmentIssue;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.UnclassifiedFolderAttachmentCount;
import com.yeodam.yeodambe.trip.service.response.UnclassifiedFolderListResponse;
import com.yeodam.yeodambe.trip.service.response.UnclassifiedAttachmentListResponse;
import com.yeodam.yeodambe.trip.service.request.AttachmentCursor;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UnclassifiedFolderListService {
    private static final int PAGE_SIZE = 18;
    private static final int QUERY_SIZE = PAGE_SIZE + 1;

    private final TripAccessService tripAccessService;
    private final TripAttachmentRepository tripAttachmentRepository;
    private final TripAttachmentStorageClient tripAttachmentStorageClient;
    private final ObjectMapper objectMapper;

    public UnclassifiedAttachmentListResponse findAttachments(
            Long userId,
            Long tripId,
            String issue,
            String cursor
    ) {
        AttachmentIssue attachmentIssue = parseIssue(issue);
        tripAccessService.requireReadableTrip(tripId, userId);
        AttachmentCursor attachmentCursor = AttachmentCursor.decode(cursor, objectMapper);

        List<TripAttachment> attachments = tripAttachmentRepository.findUnclassifiedByIssueWithCursor(
                tripId,
                attachmentIssue,
                attachmentCursor == null ? null : attachmentCursor.createdAt(),
                attachmentCursor == null ? null : attachmentCursor.tripAttachmentId(),
                PageRequest.of(0, QUERY_SIZE)
        );
        long attachmentCount = tripAttachmentRepository.countUnclassifiedByIssue(tripId).stream()
                .filter(count -> count.issue() == attachmentIssue)
                .map(UnclassifiedFolderAttachmentCount::attachmentCount)
                .findFirst()
                .orElse(0L);

        List<UnclassifiedAttachmentListResponse.Item> items = attachments.stream()
                .limit(PAGE_SIZE)
                .map(attachment -> new UnclassifiedAttachmentListResponse.Item(
                        attachment.getId(),
                        tripAttachmentStorageClient.createReadUrl(attachment.getPreviewStorageKey()),
                        attachment.getIssue(),
                        attachment.getTripPlaceId()
                ))
                .toList();

        boolean hasNext = attachments.size() > PAGE_SIZE;
        String nextCursor = null;

        if (hasNext) {
            TripAttachment lastAttachment = attachments.get(PAGE_SIZE - 1);
            nextCursor = new AttachmentCursor(
                    lastAttachment.getCreatedAt(),
                    lastAttachment.getId()
            ).encode(objectMapper);
        }

        return new UnclassifiedAttachmentListResponse(
                attachmentIssue,
                folderName(attachmentIssue),
                attachmentCount,
                items,
                hasNext,
                nextCursor
        );
    }

    private AttachmentIssue parseIssue(String issue) {
        return switch (issue) {
            case "UNCLEAR_LOCATION" -> AttachmentIssue.UNCLEAR_LOCATION;
            case "BLURRY" -> AttachmentIssue.BLURRY;
            case "DUPLICATED" -> AttachmentIssue.DUPLICATED;
            case null, default -> throw new InvalidAttachmentIssueException();
        };
    }

    public UnclassifiedFolderListResponse findFolders(Long userId, Long tripId) {
        tripAccessService.requireReadableTrip(tripId, userId);

        Map<AttachmentIssue, Long> counts = tripAttachmentRepository
                .countUnclassifiedByIssue(tripId)
                .stream()
                .collect(Collectors.toMap(
                        UnclassifiedFolderAttachmentCount::issue,
                        UnclassifiedFolderAttachmentCount::attachmentCount
                ));

        List<UnclassifiedFolderListResponse.Folder> folders = new ArrayList<>();

        for (AttachmentIssue issue : List.of(
                AttachmentIssue.UNCLEAR_LOCATION,
                AttachmentIssue.BLURRY,
                AttachmentIssue.DUPLICATED
        )) {
            long count = counts.getOrDefault(issue, 0L);
            UnclassifiedFolderListResponse.RepresentativeAttachment representative = null;

            if (count > 0) {
                List<TripAttachment> attachments = tripAttachmentRepository
                        .findUnclassifiedRepresentatives(
                                tripId, issue, PageRequest.of(0, 1)
                        );

                if (!attachments.isEmpty()) {
                    TripAttachment attachment = attachments.getFirst();
                    representative =
                            new UnclassifiedFolderListResponse.RepresentativeAttachment(
                                    attachment.getId(),
                                    tripAttachmentStorageClient.createReadUrl(
                                            attachment.getPreviewStorageKey()
                                    )
                            );
                }
            }

            folders.add(new UnclassifiedFolderListResponse.Folder(
                    issue, folderName(issue), count, representative
            ));
        }

        return new UnclassifiedFolderListResponse(folders);
    }

    private String folderName(AttachmentIssue issue) {
        return switch (issue) {
            case UNCLEAR_LOCATION -> "장소가 명확하지 않은 첨부";
            case BLURRY -> "흐릿한 첨부";
            case DUPLICATED -> "비슷한 첨부";
            case NONE -> throw new IllegalArgumentException();
        };
    }
}