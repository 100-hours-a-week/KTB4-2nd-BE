package com.yeodam.websocket.common.websocket.security;

import com.yeodam.websocket.common.websocket.client.BackendAuthenticationClient;
import com.yeodam.websocket.common.websocket.client.response.BackendAuthenticationResponse.AuthenticationData;
import com.yeodam.websocket.common.websocket.exception.BackendAuthenticationException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.util.List;

public class WebSocketHandshakeAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log =
            LoggerFactory.getLogger(WebSocketHandshakeAuthenticationFilter.class);

    private final BackendAuthenticationClient authenticationClient;

    public WebSocketHandshakeAuthenticationFilter(
            BackendAuthenticationClient authenticationClient
    ) {
        this.authenticationClient = authenticationClient;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.GET.matches(request.getMethod())
                || !"/ws".equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            AuthenticationData data = authenticationClient.authenticate(
                    cookieValue(request, "accessToken"),
                    cookieValue(request, "CSRF_CONTEXT")
            );

            WebSocketPrincipal principal = new WebSocketPrincipal(
                    data.userId(),
                    data.sessionId(),
                    data.accessTokenExpiresAt()
            );

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(
                            principal,
                            null,
                            List.of()
                    )
            );
            SecurityContextHolder.setContext(context);
        } catch (BackendAuthenticationException exception) {
            SecurityContextHolder.clearContext();

            log.warn(
                    "WebSocket handshake authentication rejected. failure={}",
                    exception.failure()
            );

            HttpStatus status = switch (exception.failure()) {
                case USER_UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
                case CSRF_INVALID -> HttpStatus.FORBIDDEN;
                case SERVICE_FORBIDDEN, SERVICE_TOKEN_MISSING,
                     BACKEND_UNAVAILABLE, INVALID_RESPONSE ->
                        HttpStatus.SERVICE_UNAVAILABLE;
            };

            response.setStatus(status.value());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String cookieValue(HttpServletRequest request, String name) {
        Cookie cookie = WebUtils.getCookie(request, name);
        return cookie == null ? null : cookie.getValue();
    }
}