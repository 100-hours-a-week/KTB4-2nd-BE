package com.yeodam.yeodambe.user.security.websocket;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RequiredArgsConstructor
public class WebSocketServiceAuthenticationFilter
        extends OncePerRequestFilter {

    private final WebSocketAuthenticationProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");

        String expected = properties.internalServiceToken();
        String provided = request.getHeader("X-Internal-Service-Token");

        if (expected == null || expected.isBlank()
                || provided == null
                || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        )) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(
                    objectMapper.writeValueAsString(
                            new ApiResponse<Void>(ErrorMessage.FORBIDDEN, null)
                    )
            );
            return;
        }

        filterChain.doFilter(request, response);
    }
}