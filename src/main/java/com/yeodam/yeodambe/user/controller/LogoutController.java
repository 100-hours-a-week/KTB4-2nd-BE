package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.user.service.LogoutService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
public class LogoutController {

    private final LogoutService service;
    private final boolean cookieSecure;

    public LogoutController(
            LogoutService service,
            @Value("${oauth.browser-context-cookie.secure}") boolean cookieSecure
    ) {
        this.service = service;
        this.cookieSecure = cookieSecure;
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal Jwt jwt
    ) {
        service.logout(jwt.getClaimAsString("sid"));

        ResponseCookie accessCookie = expiredCookie(
                "accessToken",
                "/"
        );
        ResponseCookie refreshCookie = expiredCookie(
                "refreshToken",
                "/auth"
        );

        return ResponseEntity.noContent()
                .header(
                        HttpHeaders.SET_COOKIE,
                        accessCookie.toString(),
                        refreshCookie.toString()
                )
                .build();
    }

    private ResponseCookie expiredCookie(
            String name,
            String path
    ) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path(path)
                .maxAge(Duration.ZERO)
                .build();
    }
}