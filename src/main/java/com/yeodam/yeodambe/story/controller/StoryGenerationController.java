package com.yeodam.yeodambe.story.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.story.service.StoryGenerationAcceptanceService;
import com.yeodam.yeodambe.story.service.StoryGenerationCancelService;
import com.yeodam.yeodambe.story.service.StoryGenerationStatusService;
import com.yeodam.yeodambe.story.service.request.StoryGenerationRequest;
import com.yeodam.yeodambe.story.service.response.StoryGenerationAcceptedResponse;
import com.yeodam.yeodambe.story.service.response.StoryGenerationStatusResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class StoryGenerationController {

    private final StoryGenerationAcceptanceService acceptanceService;
    private final StoryGenerationStatusService statusService;
    private final StoryGenerationCancelService cancelService;

    @DeleteMapping("/story-generations/{generationId}")
    public ResponseEntity<Void> cancel(
            @PathVariable Long generationId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());
        cancelService.cancel(userId, generationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/story-generation/{generationId}")
    public ResponseEntity<ApiResponse<StoryGenerationStatusResponse>> getStatus(
            @PathVariable Long generationId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());
        var response = statusService.getStatus(userId, generationId);

        return ResponseEntity.ok().body(
                new ApiResponse<>(SuccessMessage.STORY_GENERATION_STATUS_FOUND, response)
        );
    }

    @PostMapping("/trips/{tripId}/story-generations")
    public ResponseEntity<ApiResponse<StoryGenerationAcceptedResponse>> create(
            @PathVariable Long tripId,
            @RequestBody StoryGenerationRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());
        String requestId = MDC.get("request_id");
        var job = acceptanceService.accept(userId, tripId, request, requestId);

        return ResponseEntity.accepted().body(
                new ApiResponse<>(
                        SuccessMessage.STORY_GENERATION_ACCEPTED,
                        new StoryGenerationAcceptedResponse(job.getId(), job.getTripId(), job.getStatus())
                )
        );
    }
}
