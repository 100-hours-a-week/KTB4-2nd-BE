package com.yeodam.yeodambe.user.security.ratelimit;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Register only inside SecurityFilterChain, after AuthorizationFilter. */
public final class UserReadRateLimitFilter extends OncePerRequestFilter {
    private static final Set<String> EXCLUDED_PATHS = Set.of(
            "/actuator/health", "/auth/csrf", "/auth/kakao/authorize", "/auth/kakao/callback");
    private final UserReadRateLimiter limiter;
    private final ObjectMapper objectMapper;

    public UserReadRateLimitFilter(UserReadRateLimiter limiter, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return EXCLUDED_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) {
            // Invalid/missing tokens must be handled by the existing authentication/authorization chain.
            chain.doFilter(request, response);
            return;
        }
        long userId = Long.parseLong(jwt.getToken().getSubject());
        UserReadRateLimiter.Decision decision = limiter.acquire(userId);
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(decision.capacityExceeded() ? 503 : 429);
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        response.setHeader("X-RateLimit-Scope", "user-read");
        response.setHeader("Cache-Control", "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (!"HEAD".equals(request.getMethod())) {
            response.getWriter().write(objectMapper.writeValueAsString(new ApiResponse<Void>(
                    decision.capacityExceeded() ? ErrorMessage.READ_RATE_LIMIT_STORE_FULL
                            : ErrorMessage.READ_RATE_LIMIT_EXCEEDED, null)));
        }
    }
}
