package com.yeodam.yeodambe.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@ConditionalOnProperty(prefix = "app.request-rate-limit", name = "enabled", havingValue = "true")
public class RequestRateLimitInterceptor implements HandlerInterceptor {

    private final RequestRateLimitProperties properties;
    private final RedisTokenBucketRateLimiter rateLimiter;

    public RequestRateLimitInterceptor(
            RequestRateLimitProperties properties,
            RedisTokenBucketRateLimiter rateLimiter
    ) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        if (!properties.enabled()) {
            return true;
        }

        RequestRateLimitGroup group = groupFor(request);
        if (group == null) {
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new AuthenticationCredentialsNotFoundException(RequestRateLimitErrorMessage.AUTHENTICATION_REQUIRED.message());
        }

        long userId = Long.parseLong(jwt.getSubject());
        long retryAfterSeconds = rateLimiter.tryAcquire(group, userId);
        if (retryAfterSeconds > 0) {
            throw new RequestRateLimitExceededException(group, retryAfterSeconds);
        }
        return true;
    }

    private RequestRateLimitGroup groupFor(HttpServletRequest request) {
        String path = request.getServletPath();
        if ("POST".equals(request.getMethod())) {
            if ("/attachments/bulk-download".equals(path)) {
                return RequestRateLimitGroup.ATTACHMENT_BULK_DOWNLOAD;
            }
            if (path.matches("/stories/[^/]+/tone-preview")) {
                return RequestRateLimitGroup.STORY_TONE_PREVIEW;
            }
        }
        if (!"/trips".equals(path)) {
            return null;
        }
        return switch (request.getMethod()) {
            case "GET" -> RequestRateLimitGroup.TRIP_READ;
            case "POST" -> RequestRateLimitGroup.TRIP_CREATE;
            default -> null;
        };
    }
}
