package com.yeodam.yeodambe.story.service.response;

import java.time.LocalDate;
import java.util.List;

public record StoryPreparationSnapshot(
        String initialContentHash,
        List<SourcePlace> sourcePlaces
) {
    public record SourcePlace(
            Long tripPlaceId,
            LocalDate sourceDate
    ) {
    }
}
