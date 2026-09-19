package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.user.service.AccessTokenRefreshService;
import com.yeodam.yeodambe.user.service.response.AccessTokenRefreshResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
public class AccessTokenRefreshController {

    private final AccessTokenRefreshService service;
    private final boolean cookieSecure;

    public AccessTokenRefreshController(
            AccessTokenRefreshService service,
            @Value("${oauth.browser-context-cookie.secure}") boolean cookieSecure
    ) {
        this.service = service;
        this.cookieSecure = cookieSecure;
    }

    @PostMapping("/auth/token/refresh")
    public ResponseEntity<ApiResponse<AccessTokenRefreshResponse>> refresh(
            @CookieValue(
                    name = "refreshToken",
                    required = false
            ) String refreshToken
    ) {
        AccessTokenRefreshService.Result result =
                service.refresh(refreshToken);

        ResponseCookie accessCookie = cookie(
                "accessToken",
                result.accessToken(),
                "/",
                1800
        );

        ResponseCookie refreshCookie = cookie(
                "refreshToken",
                result.refreshToken(),
                "/auth",
                604800
        );

        AccessTokenRefreshResponse data =
                new AccessTokenRefreshResponse(1800);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.SET_COOKIE,
                        accessCookie.toString(),
                        refreshCookie.toString()
                )
                .body(new ApiResponse<>(
                        "TOKEN_REFRESH_SUCCESS",
                        data
                ));
    }

    private ResponseCookie cookie(
            String name,
            String value,
            String path,
            long maxAgeSeconds
    ) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path(path)
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
    }
}