package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.user.security.CookiePathResolver;
import com.yeodam.yeodambe.user.exception.OnboardingTokenRequiredException;
import com.yeodam.yeodambe.user.service.ProfileRegistrationService;
import com.yeodam.yeodambe.user.service.request.ProfileRegistrationRequest;
import com.yeodam.yeodambe.user.service.response.ProfileRegistrationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
public class ProfileRegistrationController {

    private final ProfileRegistrationService service;
    private final boolean cookieSecure;
    private final CookiePathResolver cookiePathResolver;

    public ProfileRegistrationController(
            ProfileRegistrationService service,
            @Value("${oauth.browser-context-cookie.secure}") boolean cookieSecure,
            CookiePathResolver cookiePathResolver
    ) {
        this.service = service;
        this.cookieSecure = cookieSecure;
        this.cookiePathResolver = cookiePathResolver;
    }

    @PostMapping("/users/me/profile")
    public ResponseEntity<ApiResponse<ProfileRegistrationResponse>> register(
            @RequestBody(required = false) ProfileRegistrationRequest request,
            @CookieValue(name = "profileToken", required = false) String profileToken,
            @CookieValue(name = "accessToken", required = false) String accessToken
    ) {
        if ((profileToken == null || profileToken.isBlank())
                && accessToken != null && !accessToken.isBlank()) {
            throw new OnboardingTokenRequiredException();
        }

        ProfileRegistrationService.Result result = service.register(
                profileToken,
                request == null ? null : request.nickname()
        );

        ResponseCookie accessCookie = cookie(
                "accessToken", result.accessToken(), "/", 1800
        );
        ResponseCookie refreshCookie = cookie(
                "refreshToken", result.refreshToken(), cookiePathResolver.apiPath("/auth"), 604800
        );
        ResponseCookie clearedProfileCookie = cookie(
                "profileToken", "", cookiePathResolver.apiPath("/users/me/profile"), 0
        );

        ProfileRegistrationResponse data = new ProfileRegistrationResponse(
                result.userId(),
                result.nickname(),
                1800
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.SET_COOKIE,
                        accessCookie.toString(),
                        refreshCookie.toString(),
                        clearedProfileCookie.toString()
                )
                .body(new ApiResponse<>("ONBOARDING_SUCCESS", data));
    }

    private ResponseCookie cookie(
            String name, String value, String path, long maxAgeSeconds
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
