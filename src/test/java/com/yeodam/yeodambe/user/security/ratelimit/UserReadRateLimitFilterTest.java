package com.yeodam.yeodambe.user.security.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserReadRateLimitFilterTest {
    private final UserReadRateLimitFilter filter = new UserReadRateLimitFilter(
            new UserReadRateLimiter(5, 0, 2, () -> 0L), new ObjectMapper());

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    private void authenticate(long userId, String sid) {
        Jwt jwt = Jwt.withTokenValue("test-only").header("alg", "HS256")
                .subject(Long.toString(userId)).claim("sid", sid).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private MockHttpServletResponse request(String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, "/api" + path);
        request.setContextPath("/api");
        request.setRemoteAddr("192.0.2.1");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> response.setStatus(200));
        return response;
    }

    @Test
    void sameIpDifferentUsersAreIndependentAndSameUserDifferentSessionsShareQuota() throws Exception {
        authenticate(1, "session-a");
        assertThat(request("GET", "/trips").getStatus()).isEqualTo(200);
        authenticate(1, "session-b");
        var rejected = request("GET", "/trips/map");
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("1");
        assertThat(rejected.getHeader("X-RateLimit-Scope")).isEqualTo("user-read");
        assertThat(rejected.getContentAsString()).contains("READ_RATE_LIMIT_EXCEEDED");
        authenticate(2, "session-c");
        assertThat(request("GET", "/trips").getStatus()).isEqualTo(200);
    }

    @Test
    void authAndHealthExceptionsAndWritesDoNotConsumeReadQuota() throws Exception {
        authenticate(1, "session-a");
        for (String path : List.of("/actuator/health", "/auth/csrf", "/auth/kakao/authorize",
                "/auth/kakao/callback")) {
            assertThat(request("GET", path).getStatus()).isEqualTo(200);
            assertThat(request("HEAD", path).getStatus()).isEqualTo(200);
        }
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "OPTIONS")) {
            assertThat(request(method, "/trips").getStatus()).isEqualTo(200);
        }
        assertThat(request("GET", "/trips").getStatus()).isEqualTo(200);
        assertThat(request("GET", "/trips/1/attachments").getStatus()).isEqualTo(429);
    }

    @Test
    void headUsesSameQuotaButHasNoResponseBody() throws Exception {
        authenticate(1, "session-a");
        assertThat(request("GET", "/trips").getStatus()).isEqualTo(200);
        var head = request("HEAD", "/trips");
        assertThat(head.getStatus()).isEqualTo(429);
        assertThat(head.getContentAsByteArray()).isEmpty();
    }

    @Test
    void absentAuthenticationDoesNotAllocateUserQuota() throws Exception {
        assertThat(request("GET", "/trips").getStatus()).isEqualTo(200); // Authentication belongs to SecurityConfig.
        authenticate(1, "session-a");
        assertThat(request("GET", "/trips").getStatus()).isEqualTo(200);
    }

    @Test
    void storeCapacityFailureIs503InsteadOfAnotherUsers429() throws Exception {
        authenticate(1, "a"); request("GET", "/trips");
        authenticate(2, "b"); request("GET", "/trips");
        authenticate(3, "c");
        var response = request("GET", "/trips");
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("READ_RATE_LIMIT_STORE_FULL");
    }
}
