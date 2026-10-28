package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.user.service.ShareUserSearchService;
import com.yeodam.yeodambe.user.service.request.UserSearchRequest;
import com.yeodam.yeodambe.user.service.response.UserSearchResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ShareUserSearchController {
    private final ShareUserSearchService service;

    @GetMapping("/users/search")
    public ResponseEntity<ApiResponse<UserSearchResponse>> search(
            @Valid @ModelAttribute UserSearchRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long requesterId = Long.valueOf(jwt.getSubject());
        UserSearchResponse data = service.searchByEmail(request.email(), requesterId);
        return ResponseEntity.ok(new ApiResponse<>(SuccessMessage.USER_FOUND, data));
    }
}
