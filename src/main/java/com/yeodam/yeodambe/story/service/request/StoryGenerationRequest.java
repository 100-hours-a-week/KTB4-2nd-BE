package com.yeodam.yeodambe.story.service.request;

import com.yeodam.yeodambe.story.entity.Story;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.List;

public record StoryGenerationRequest(
        @NotNull
        Story.Mood mood,

        @NotNull
        @Size(min = 1, max = 10)
        List<@NotNull @Positive Long> tripPlaceIds
) {
        @AssertTrue
        public boolean isTripPlaceIdsUnique() {
                return tripPlaceIds == null
                        || new HashSet<>(tripPlaceIds).size() == tripPlaceIds.size();
        }
}
