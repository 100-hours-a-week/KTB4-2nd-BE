package com.yeodam.yeodambe.story.service.response;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;

public record StoryContentBlockDetail(
        StoryContentBlock block,
        TripAttachment attachment,
        StoredFile file,
        TripDetailPlace place
) {
}
