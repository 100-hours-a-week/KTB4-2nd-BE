package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.user.service.WebSocketAuthenticationService;
import com.yeodam.yeodambe.user.service.response.WebSocketAuthenticationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class WebSocketAuthenticationController {

    private final WebSocketAuthenticationService webSocketAuthenticationService;

    @GetMapping("/internal/websocket/authentication")
    public ResponseEntity<ApiResponse<WebSocketAuthenticationResponse>> authenticate(
            @AuthenticationPrincipal Jwt jwt,
            @CookieValue(name = "CSRF_CONTEXT", required = false)
            String browserContext
    ) {
        WebSocketAuthenticationResponse data =
                webSocketAuthenticationService.authenticate(jwt, browserContext);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>(
                        SuccessMessage.WEBSOCKET_AUTHENTICATION_SUCCESS,
                        data
                ));
    }
}