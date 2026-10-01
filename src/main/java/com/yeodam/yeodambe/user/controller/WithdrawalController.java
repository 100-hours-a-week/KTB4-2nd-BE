package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.user.security.CookiePathResolver;
import com.yeodam.yeodambe.user.service.WithdrawalService;
import org.springframework.http.ResponseEntity;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

@RestController
public class WithdrawalController {

    private final WithdrawalService withdrawalService;
    private final CookiePathResolver cookiePathResolver;
    private final boolean cookieSecure;

    public WithdrawalController(
            WithdrawalService withdrawalService,
            CookiePathResolver cookiePathResolver,
            @Value("${oauth.browser-context-cookie.secure}") boolean cookieSecure
    ) {
        this.withdrawalService = withdrawalService;
        this.cookiePathResolver = cookiePathResolver;
        this.cookieSecure = cookieSecure;
    }

    @DeleteMapping("/users/me")
    public ResponseEntity<Void> withdraw(
            @AuthenticationPrincipal Jwt jwt,
            @CookieValue(name = "CSRF_CONTEXT", required = false) String csrfContext
    ) {
        withdrawalService.withdraw(
                Long.valueOf(jwt.getSubject()),
                csrfContext
        );
        return ResponseEntity.noContent()
                .header(
                        HttpHeaders.SET_COOKIE,
                        expiredCookie("accessToken", "/").toString(),
                        expiredCookie(
                                "refreshToken",
                                cookiePathResolver.apiPath("/auth")
                        ).toString()
                )
                .build();
    }

    private ResponseCookie expiredCookie(String name, String path) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path(path)
                .maxAge(Duration.ZERO)
                .build();
    }
}