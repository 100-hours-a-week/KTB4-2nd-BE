package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.AttachmentIssue;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.UnclassifiedFolderAttachmentCount;
import com.yeodam.yeodambe.trip.service.response.UnclassifiedFolderListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UnclassifiedFolderListService {
    private final TripAccessService tripAccessService;
    private final TripAttachmentRepository tripAttachmentRepository;
    private final TripAttachmentStorageClient tripAttachmentStorageClient;

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