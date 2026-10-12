package com.yeodam.yeodambe.story.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.story.service.StoryGenerationAcceptanceService;
import com.yeodam.yeodambe.story.service.StoryGenerationPublicationService;
import com.yeodam.yeodambe.story.service.request.StoryGenerationRequest;
import com.yeodam.yeodambe.story.service.response.StoryGenerationAcceptedResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

@RestController
@RequiredArgsConstructor
public class StoryGenerationController {

    private final StoryGenerationAcceptanceService acceptanceService;
    private final StoryGenerationPublicationService publicationService;

    @PostMapping("/trips/{tripId}/story-generations")
    public CompletableFuture<ResponseEntity<ApiResponse<StoryGenerationAcceptedResponse>>> create(
            @PathVariable Long tripId,
            @RequestBody StoryGenerationRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());
        String requestId = MDC.get("request_id");
        var job = acceptanceService.accept(userId, tripId, request, requestId);

        return publicationService.publish(job.getId())
                .thenApply(ignored -> ResponseEntity.accepted().body(
                        new ApiResponse<>(
                                SuccessMessage.STORY_GENERATION_ACCEPTED,
                                new StoryGenerationAcceptedResponse(job.getId(), job.getTripId(), job.getStatus())
                        )
                ));
    }
}
