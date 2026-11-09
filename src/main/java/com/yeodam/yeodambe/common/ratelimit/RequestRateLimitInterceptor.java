package com.yeodam.yeodambe.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
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
            throw new AuthenticationCredentialsNotFoundException("요청 제한에 필요한 인증 정보가 없습니다.");
        }

        long userId = Long.parseLong(jwt.getSubject());
        long retryAfterSeconds = rateLimiter.tryAcquire(group, userId);
        if (retryAfterSeconds > 0) {
            throw new RequestRateLimitExceededException(group, retryAfterSeconds);
        }
        return true;
    }

    private RequestRateLimitGroup groupFor(HttpServletRequest request) {
        if (!"/trips".equals(request.getServletPath())) {
            return null;
        }
        return switch (request.getMethod()) {
            case "GET" -> RequestRateLimitGroup.TRIP_READ;
            case "POST" -> RequestRateLimitGroup.TRIP_CREATE;
            default -> null;
        };
    }
}
