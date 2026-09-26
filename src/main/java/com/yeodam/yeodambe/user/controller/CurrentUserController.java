package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.user.service.CurrentUserService;
import com.yeodam.yeodambe.user.service.response.CurrentUserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CurrentUserController {

    private final CurrentUserService service;

    @GetMapping("/users/me")
    public ResponseEntity<ApiResponse<CurrentUserResponse>> find(
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());

        CurrentUserResponse data = service.find(userId);

        return ResponseEntity.ok(
                new ApiResponse<>(SuccessMessage.USER_FOUND, data)
        );
    }
}
