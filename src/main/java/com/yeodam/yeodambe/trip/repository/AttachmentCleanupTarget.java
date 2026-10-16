package com.yeodam.yeodambe.trip.repository;

import java.time.LocalDateTime;

public record AttachmentCleanupTarget(Long attachmentId, LocalDateTime deletedAt) {
}
