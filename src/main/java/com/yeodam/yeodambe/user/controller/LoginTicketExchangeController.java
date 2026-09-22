package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.user.security.CookiePathResolver;
import com.yeodam.yeodambe.user.exception.LoginTicketInvalidOrExpiredException;
import com.yeodam.yeodambe.user.service.LoginExchangeDecision;
import com.yeodam.yeodambe.user.service.LoginTicketExchangeService;
import com.yeodam.yeodambe.user.service.request.LoginTicketExchangeRequest;
import com.yeodam.yeodambe.user.service.response.LoginExchangeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
public class LoginTicketExchangeController {

    private final LoginTicketExchangeService service;
    private final boolean cookieSecure;
    private final CookiePathResolver cookiePathResolver;

    public LoginTicketExchangeController(
            LoginTicketExchangeService service,
            @Value("${oauth.browser-context-cookie.secure}") boolean cookieSecure,
            CookiePathResolver cookiePathResolver
    ) {
        this.service = service;
        this.cookieSecure = cookieSecure;
        this.cookiePathResolver = cookiePathResolver;
    }

    @PostMapping("/auth/token/exchange")
    public ResponseEntity<ApiResponse<LoginExchangeResponse>> exchange(
            @RequestBody(required = false) LoginTicketExchangeRequest request,
            @CookieValue(name = "OAUTH_BROWSER_CONTEXT", required = false) String browserContext
    ) {
        if (request == null || request.loginTicket() == null || request.loginTicket().isBlank()) {
            throw new LoginTicketInvalidOrExpiredException();
        }

        LoginExchangeDecision decision = service.exchange(request.loginTicket(), browserContext);

        if (decision instanceof LoginExchangeDecision.ExistingMember member) {
            ResponseCookie accessCookie = cookie("accessToken", member.accessToken(), "/", 1800);
            ResponseCookie refreshCookie = cookie("refreshToken", member.refreshToken(), cookiePathResolver.apiPath("/auth"), 604800);
            LoginExchangeResponse data = new LoginExchangeResponse.ExistingMember(
                    1800,
                    false,
                    new LoginExchangeResponse.User(member.userId(), member.email(), member.nickname())
            );

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.SET_COOKIE, accessCookie.toString(), refreshCookie.toString())
                    .body(new ApiResponse<>("LOGIN_SUCCESS", data));
        }

        LoginExchangeDecision.Onboarding onboarding = (LoginExchangeDecision.Onboarding) decision;
        ResponseCookie profileCookie = cookie("profileToken", onboarding.profileToken(), cookiePathResolver.apiPath("/users/me/profile"), 600);
        LoginExchangeResponse data = new LoginExchangeResponse.Onboarding(600, true);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, profileCookie.toString())
                .body(new ApiResponse<>("ONBOARDING_REQUIRED", data));
    }

    private ResponseCookie cookie(String name, String value, String path, long maxAgeSeconds) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path(path)
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
    }
}
