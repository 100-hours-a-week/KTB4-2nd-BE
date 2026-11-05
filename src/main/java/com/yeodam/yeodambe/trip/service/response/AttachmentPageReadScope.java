package com.yeodam.yeodambe.trip.service.response;

import com.yeodam.yeodambe.trip.service.request.AttachmentPageKey;

public record AttachmentPageReadScope(
        AttachmentPageKey key,
        long generation
) {
}
