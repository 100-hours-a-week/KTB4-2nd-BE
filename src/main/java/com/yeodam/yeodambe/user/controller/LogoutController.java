package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.user.service.LogoutService;
import com.yeodam.yeodambe.common.websocket.service.WebSocketAuthRevocationPublisher;
import com.yeodam.yeodambe.user.security.CookiePathResolver;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
public class LogoutController {

    private final LogoutService service;
    private final CsrfTokenStore csrfTokenStore;
    private final boolean cookieSecure;
    private final CookiePathResolver cookiePathResolver;
    private final WebSocketAuthRevocationPublisher authRevocationPublisher;

    public LogoutController(
            LogoutService service,
            CsrfTokenStore csrfTokenStore,
            @Value("${oauth.browser-context-cookie.secure}") boolean cookieSecure,
            CookiePathResolver cookiePathResolver,
            WebSocketAuthRevocationPublisher authRevocationPublisher
    ) {
        this.service = service;
        this.csrfTokenStore = csrfTokenStore;
        this.cookieSecure = cookieSecure;
        this.cookiePathResolver = cookiePathResolver;
        this.authRevocationPublisher = authRevocationPublisher;
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal Jwt jwt,
            @CookieValue(name = "CSRF_CONTEXT", required = false) String csrfContext
    ) {
        String sid = jwt.getClaimAsString("sid");
        service.logout(sid);
        authRevocationPublisher.revokeSession(Long.valueOf(jwt.getSubject()), sid);
        csrfTokenStore.delete(csrfContext);

        ResponseCookie accessCookie = expiredCookie(
                "accessToken",
                "/"
        );
        ResponseCookie refreshCookie = expiredCookie(
                "refreshToken",
                cookiePathResolver.apiPath("/auth")
        );
        ResponseCookie csrfContextCookie = expiredCookie(
                "CSRF_CONTEXT",
                "/"
        );
        ResponseCookie oauthBrowserContextCookie = expiredCookie(
                "OAUTH_BROWSER_CONTEXT",
                cookiePathResolver.apiPath("/auth")
        );

        return ResponseEntity.noContent()
                .header(
                        HttpHeaders.SET_COOKIE,
                        accessCookie.toString(),
                        refreshCookie.toString(),
                        csrfContextCookie.toString(),
                        oauthBrowserContextCookie.toString()
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
