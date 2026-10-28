package com.yeodam.yeodambe.user.security.ratelimit;

import com.yeodam.yeodambe.common.logging.RequestIdFilter;
import com.yeodam.yeodambe.user.security.SecurityConfig;
import com.yeodam.yeodambe.user.security.csrf.CsrfAccessDeniedHandler;
import com.yeodam.yeodambe.user.security.csrf.RdbCsrfTokenRepository;
import com.yeodam.yeodambe.user.security.jwt.ApiAuthenticationEntryPoint;
import com.yeodam.yeodambe.user.security.jwt.CookieAccessTokenResolver;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = UserReadRateLimitSecurityTest.Endpoints.class, properties = {
        "app.cors.allowed-origin=https://v1-staging.yeodam-2gether.com",
        "app.read-rate-limit.enabled=true",
        "app.read-rate-limit.requests-per-second=1", "app.read-rate-limit.burst=0"})
@ContextConfiguration(classes = UserReadRateLimitSecurityTest.Endpoints.class)
@Import({SecurityConfig.class, CookieAccessTokenResolver.class,
        ApiAuthenticationEntryPoint.class, CsrfAccessDeniedHandler.class, RequestIdFilter.class})
class UserReadRateLimitSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean RdbCsrfTokenRepository csrfRepository;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach
    void setup() {
        given(csrfRepository.loadDeferredToken(any(), any())).willAnswer(invocation ->
                new HttpSessionCsrfTokenRepository().loadDeferredToken(
                        invocation.getArgument(0), invocation.getArgument(1)));
        given(decoder.decode("valid-a")).willReturn(token("1", "a"));
        given(decoder.decode("valid-b")).willReturn(token("1", "b"));
        given(decoder.decode("valid-other")).willReturn(token("2", "c"));
        given(decoder.decode("forged")).willThrow(new BadJwtException("invalid signature"));
    }

    private Jwt token(String user, String sid) {
        return Jwt.withTokenValue("test-only").header("alg", "HS256")
                .subject(user).claim("sid", sid).build();
    }

    @Test
    void realSecurityChainRejectsUnauthenticatedAndForgedTokensBeforeReadLimiter() throws Exception {
        mvc.perform(get("/trips").header("X-User-ID", "1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/trips").cookie(new Cookie("accessToken", "forged")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/trips").cookie(new Cookie("accessToken", "valid-other")))
                .andExpect(status().isOk());
    }

    @Test
    void securityChainSharesQuotaAcrossSessionsAndKeepsWritesAndExceptionsAvailable() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/trips").cookie(new Cookie("accessToken", "valid-a")))
                .andExpect(status().isOk());
        mvc.perform(get("/trips/map").cookie(new Cookie("accessToken", "valid-b"))
                        .header("X-Request-ID", "read-limit-test"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Scope", "user-read"))
                .andExpect(header().string("X-Request-ID", "read-limit-test"))
                .andExpect(jsonPath("$.message").value("READ_RATE_LIMIT_EXCEEDED"));
        mvc.perform(get("/actuator/health").servletPath("/actuator/health")
                        .cookie(new Cookie("accessToken", "forged")))
                .andExpect(status().isOk());
        mvc.perform(post("/trips").cookie(new Cookie("accessToken", "valid-a")).with(csrf()))
                .andExpect(status().isOk());
    }

    @RestController
    static class Endpoints {
        @GetMapping({"/trips", "/trips/map", "/actuator/health"})
        String read() { return "ok"; }
        @PostMapping("/trips")
        String write() { return "ok"; }
    }
}
