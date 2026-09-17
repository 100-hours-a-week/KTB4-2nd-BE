package com.yeodam.yeodambe.user.controller;


import com.yeodam.yeodambe.user.service.KakaoLoginStartService;
import com.yeodam.yeodambe.user.service.KakaoLoginCallbackService;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.springframework.http.HttpStatus.FOUND;

@RestController
@RequestMapping("/auth/kakao")
public class KakaoAuthController {
    private static final String BROWSER_CONTEXT_COOKIE = "OAUTH_BROWSER_CONTEXT";
    private final KakaoLoginStartService loginStartService;
    private final boolean cookieSecure;
    private final KakaoLoginCallbackService loginCallbackService;
    private final String frontendCallbackUri;

    public KakaoAuthController(
            KakaoLoginStartService loginStartService,
            KakaoLoginCallbackService loginCallbackService,
            @Value("${oauth.frontend-callback-uri}")
            String frontendCallbackUri,
            @Value("${oauth.browser-context-cookie.secure}")
            boolean cookieSecure
    ) {
        this.loginStartService = loginStartService;
        this.loginCallbackService = loginCallbackService;
        this.frontendCallbackUri = frontendCallbackUri;
        this.cookieSecure = cookieSecure;
    }
    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(
            @CookieValue(
                    name = BROWSER_CONTEXT_COOKIE,
                    required = false
            )
            String browserContext
    ) {
        boolean browserContextCreated =
                browserContext == null || browserContext.isBlank();

        if (browserContextCreated) {
            browserContext = UUID.randomUUID().toString();
        }

        String authorizationUrl =
                loginStartService.start(browserContext);

        ResponseEntity.BodyBuilder response = ResponseEntity
                .status(FOUND)
                .location(URI.create(authorizationUrl))
                .cacheControl(CacheControl.noStore());

        if (browserContextCreated) {
            ResponseCookie cookie = ResponseCookie
                    .from(BROWSER_CONTEXT_COOKIE, browserContext)
                    .httpOnly(true)
                    .secure(cookieSecure)
                    .sameSite("Lax")
                    .path("/auth")
                    .maxAge(Duration.ofMinutes(10))
                    .build();

            response.header(
                    HttpHeaders.SET_COOKIE,
                    cookie.toString()
            );
        }

        return response.build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam String code,
            @RequestParam String state,
            @CookieValue(
                    name = BROWSER_CONTEXT_COOKIE,
                    required = false
            )
            String browserContext
    ) {
        String loginTicket =
                loginCallbackService.issueLoginTicket(
                        code,
                        state,
                        browserContext
                );

        URI redirectUri = UriComponentsBuilder
                .fromUriString(frontendCallbackUri)
                .queryParam(
                        "loginTicket",
                        "{loginTicket}"
                )
                .encode()
                .buildAndExpand(loginTicket)
                .toUri();

        return ResponseEntity
                .status(FOUND)
                .location(redirectUri)
                .cacheControl(CacheControl.noStore())
                .header(
                        "Referrer-Policy",
                        "no-referrer"
                )
                .build();
    }
}
