package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.user.service.CsrfTokenService;
import com.yeodam.yeodambe.user.service.response.CsrfTokenResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

@RestController
public class CsrfController {

    private static final String CONTEXT_COOKIE = "CSRF_CONTEXT";

    private final CsrfTokenService csrfTokenService;
    private final boolean cookieSecure;

    public CsrfController(
            CsrfTokenService csrfTokenService,
            @Value("${oauth.browser-context-cookie.secure}") boolean cookieSecure
    ) {
        this.csrfTokenService = csrfTokenService;
        this.cookieSecure = cookieSecure;
    }

    @GetMapping("/auth/csrf")
    public ResponseEntity<ApiResponse<CsrfTokenResponse>> issue(
            @CookieValue(name = CONTEXT_COOKIE, required = false) String browserContext
    ) {
        boolean contextCreated = browserContext == null || browserContext.isBlank();
        if (contextCreated) {
            browserContext = UUID.randomUUID().toString();
        }

        String token = csrfTokenService.issue(browserContext);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.noStore());

        if (contextCreated) {
            ResponseCookie cookie = ResponseCookie.from(CONTEXT_COOKIE, browserContext)
                    .httpOnly(true)
                    .secure(cookieSecure)
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(Duration.ofDays(7))
                    .build();
            response.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }

        return response.body(new ApiResponse<>(
                SuccessMessage.CSRF_TOKEN_ISSUED,
                new CsrfTokenResponse("X-CSRF-TOKEN", token)
        ));
    }
}
