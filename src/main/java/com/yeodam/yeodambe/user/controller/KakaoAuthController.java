package com.yeodam.yeodambe.user.controller;


import com.yeodam.yeodambe.user.service.KakaoLoginStartService;
import com.yeodam.yeodambe.user.security.CookiePathResolver;
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
import com.yeodam.yeodambe.user.exception.KakaoAuthenticationFailedException;
import com.yeodam.yeodambe.user.exception.LoginTicketIssueFailedException;
import com.yeodam.yeodambe.user.exception.OAuthProviderUnavailableException;
import com.yeodam.yeodambe.user.exception.OAuthStateInvalidOrExpiredException;
import org.springframework.dao.DataAccessResourceFailureException;

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
    private final CookiePathResolver cookiePathResolver;

    public KakaoAuthController(
            KakaoLoginStartService loginStartService,
            KakaoLoginCallbackService loginCallbackService,
            @Value("${oauth.frontend-callback-uri}")
            String frontendCallbackUri,
            @Value("${oauth.browser-context-cookie.secure}")
            boolean cookieSecure,
            CookiePathResolver cookiePathResolver
    ) {
        this.loginStartService = loginStartService;
        this.loginCallbackService = loginCallbackService;
        this.frontendCallbackUri = frontendCallbackUri;
        this.cookieSecure = cookieSecure;
        this.cookiePathResolver = cookiePathResolver;
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
                    .path(cookiePathResolver.apiPath("/auth"))
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
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @CookieValue(
                    name = BROWSER_CONTEXT_COOKIE,
                    required = false
            )
            String browserContext
    ) {
        if (error != null && !error.isBlank()) {
            String errorCode = "access_denied".equals(error)
                    ? "KAKAO_LOGIN_CANCELLED"
                    : "KAKAO_AUTHENTICATION_FAILED";

            return redirectToFrontend("error", errorCode);
        }
        try {
            String loginTicket =
                    loginCallbackService.issueLoginTicket(
                            code,
                            state,
                            browserContext
                    );

            return redirectToFrontend("loginTicket", loginTicket);
        } catch (OAuthStateInvalidOrExpiredException exception) {
            return redirectToFrontend(
                    "error",
                    "OAUTH_STATE_INVALID_OR_EXPIRED"
            );
        } catch (KakaoAuthenticationFailedException exception) {
            return redirectToFrontend(
                    "error",
                    "KAKAO_AUTHENTICATION_FAILED"
            );
        } catch (OAuthProviderUnavailableException exception) {
            return redirectToFrontend(
                    "error",
                    "OAUTH_PROVIDER_UNAVAILABLE"
            );
        } catch (DataAccessResourceFailureException exception) {
            return redirectToFrontend(
                    "error",
                    "AUTH_STORE_UNAVAILABLE"
            );
        } catch (LoginTicketIssueFailedException exception) {
            return redirectToFrontend(
                    "error",
                    "LOGIN_TICKET_ISSUE_FAILED"
            );
        }
    }
    private ResponseEntity<Void> redirectToFrontend(
            String parameterName,
            String parameterValue
    ) {
        URI redirectUri = UriComponentsBuilder
                .fromUriString(frontendCallbackUri)
                .queryParam(parameterName, "{parameterValue}")
                .encode()
                .buildAndExpand(parameterValue)
                .toUri();

        return ResponseEntity
                .status(FOUND)
                .location(redirectUri)
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .build();
    }
}
