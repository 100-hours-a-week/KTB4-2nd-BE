package com.yeodam.yeodambe.story.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.story.service.StoryReadService;
import com.yeodam.yeodambe.story.service.response.StoryDetailResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class StoryController {
    private final StoryReadService storyReadService;

    @GetMapping("/trips/{tripId}/story")
    public ResponseEntity<ApiResponse<StoryDetailResponse>> findStory(
            @PathVariable @Positive Long tripId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());
        StoryDetailResponse story = storyReadService.findStory(userId, tripId);
        return ResponseEntity.ok(new ApiResponse<>(SuccessMessage.STORY_FOUND, story));
    }
}
