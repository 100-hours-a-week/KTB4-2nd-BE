package com.yeodam.yeodambe.story.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.story.service.StoryReadService;
import com.yeodam.yeodambe.story.service.StorySimilarAttachmentService;

import com.yeodam.yeodambe.story.service.StoryUpdateService;
import com.yeodam.yeodambe.story.service.StoryTonePreviewService;
import com.yeodam.yeodambe.story.service.request.StoryTonePreviewRequest;
import com.yeodam.yeodambe.story.service.response.StoryTonePreviewResponse;
import com.yeodam.yeodambe.story.service.request.StoryUpdateRequest;
import com.yeodam.yeodambe.story.service.response.StoryUpdateResponse;
import jakarta.validation.Valid;
import com.yeodam.yeodambe.story.service.response.StoryDetailResponse;
import com.yeodam.yeodambe.story.service.response.StorySimilarAttachmentListResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class StoryController {
    private final StoryReadService storyReadService;
    private final StorySimilarAttachmentService storySimilarAttachmentService;
    private final StoryUpdateService storyUpdateService;
    private final StoryTonePreviewService storyTonePreviewService;

    @PostMapping("/stories/{storyId}/tone-preview")
    public ResponseEntity<ApiResponse<StoryTonePreviewResponse>> previewTone(
            @PathVariable @Positive Long storyId,
            @RequestBody StoryTonePreviewRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());
        StoryTonePreviewResponse data =
                storyTonePreviewService.preview(userId, storyId, request);
        return ResponseEntity.ok(
                new ApiResponse<>(SuccessMessage.STORY_TONE_PREVIEW_SUCCESS, data)
        );
    }

    @GetMapping("/trips/{tripId}/story")
    public ResponseEntity<ApiResponse<StoryDetailResponse>> findStory(
            @PathVariable @Positive Long tripId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());
        StoryDetailResponse story = storyReadService.findStory(userId, tripId);
        return ResponseEntity.ok(new ApiResponse<>(SuccessMessage.STORY_FOUND, story));
    }

    @GetMapping("/stories/{storyId}/blocks/{storyBlockId}/similar-attachments")
    public ResponseEntity<ApiResponse<StorySimilarAttachmentListResponse>> findSimilar(
            @PathVariable @Positive Long storyId,
            @PathVariable @Positive Long storyBlockId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());
        StorySimilarAttachmentListResponse response =
                storySimilarAttachmentService.findSimilar(userId, storyId, storyBlockId);
        return ResponseEntity.ok(new ApiResponse<>(
                SuccessMessage.SIMILAR_ATTACHMENT_LIST_FOUND, response));
    }

    @PutMapping("/stories/{storyId}")
    public ResponseEntity<ApiResponse<StoryUpdateResponse>> updateStory(
            @PathVariable @Positive Long storyId,
            @Valid @RequestBody StoryUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());

        StoryUpdateResponse data =
                storyUpdateService.update(userId, storyId, request);

        return ResponseEntity.ok(
                new ApiResponse<>(SuccessMessage.STORY_UPDATE_SUCCESS, data)
        );
    }
}
