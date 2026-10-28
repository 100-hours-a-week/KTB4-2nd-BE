package com.yeodam.yeodambe.user.security.ratelimit;

import com.yeodam.yeodambe.user.security.SecurityConfig;
import com.yeodam.yeodambe.user.security.csrf.CsrfAccessDeniedHandler;
import com.yeodam.yeodambe.user.security.csrf.RdbCsrfTokenRepository;
import com.yeodam.yeodambe.user.security.jwt.ApiAuthenticationEntryPoint;
import com.yeodam.yeodambe.user.security.jwt.CookieAccessTokenResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(value = UserReadRateLimitSecurityTest.Endpoints.class,
        properties = "app.cors.allowed-origin=https://v1-staging.yeodam-2gether.com")
@ContextConfiguration(classes = UserReadRateLimitSecurityTest.Endpoints.class)
@Import({SecurityConfig.class, CookieAccessTokenResolver.class,
        ApiAuthenticationEntryPoint.class, CsrfAccessDeniedHandler.class})
class UserReadRateLimitDisabledSecurityTest {
    @MockitoBean RdbCsrfTokenRepository csrfRepository;
    @MockitoBean JwtDecoder decoder;
    @Autowired FilterChainProxy securityChain;

    @Test
    void defaultConfigurationDoesNotRegisterTheLimiter() {
        assertThat(securityChain.getFilterChains()).allSatisfy(chain ->
                assertThat(chain.getFilters()).noneMatch(UserReadRateLimitFilter.class::isInstance));
    }
}
