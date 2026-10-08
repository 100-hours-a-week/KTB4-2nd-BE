package com.yeodam.yeodambe.story.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.story.service.StorySourceFolderListService;
import com.yeodam.yeodambe.story.service.response.StorySourceFolderListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class StorySourceFolderController {
    private final StorySourceFolderListService storySourceFolderListService;

    @GetMapping("/trips/{tripId}/story/source-folders")
    public ResponseEntity<ApiResponse<StorySourceFolderListResponse>> findFolders(
            @PathVariable Long tripId,
            @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());

        StorySourceFolderListResponse result =
                storySourceFolderListService.findFolders(userId, tripId, cursor);

        return ResponseEntity.ok(
                new ApiResponse<>(SuccessMessage.STORY_SOURCE_FOLDER_LIST_FOUND, result)
        );
    }
}
