package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.entity.AttachmentIssue;

import java.util.List;

public record TripPhotoRestorationResult(
        List<RestoredAttachment> restoredAttachments,
        long attachmentCount,
        long unclassifiedAttachmentCount,
        String thumbnailKey,
        List<PlaceChange> places,
        List<UnclassifiedFolderChange> unclassifiedFolders
) {
    public record RestoredAttachment(
            Long tripAttachmentId,
            AttachmentIssue sourceIssue,
            Long tripPlaceId
    ) {
    }

    public record PlaceChange(
            Long tripPlaceId,
            long attachmentCount,
            String thumbnailKey
    ) {
    }

    public record UnclassifiedFolderChange(
            AttachmentIssue issue,
            long attachmentCount,
            Long representativeTripAttachmentId,
            String thumbnailKey
    ) {
    }
}
