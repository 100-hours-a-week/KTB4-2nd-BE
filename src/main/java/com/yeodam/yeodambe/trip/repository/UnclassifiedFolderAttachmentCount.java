package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.AttachmentIssue;

public record UnclassifiedFolderAttachmentCount(
        AttachmentIssue issue,
        long attachmentCount
) {

}