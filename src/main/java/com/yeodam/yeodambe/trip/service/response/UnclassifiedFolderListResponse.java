package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.entity.AttachmentIssue;

import java.util.List;

public record UnclassifiedFolderListResponse(
        List<Folder> folders
) {
    public record Folder(
            AttachmentIssue issue,
            String name,
            long attachmentCount,
            RepresentativeAttachment representativeAttachment
    ) {
    }

    public record RepresentativeAttachment(
            Long tripAttachmentId,
            String thumbnailUrl
    ) {
    }
}