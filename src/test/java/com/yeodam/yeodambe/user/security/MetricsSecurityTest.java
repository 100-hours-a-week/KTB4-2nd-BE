package com.yeodam.yeodambe.user.security;

import com.yeodam.yeodambe.user.security.csrf.CsrfAccessDeniedHandler;
import com.yeodam.yeodambe.user.security.csrf.RedisCsrfTokenRepository;
import com.yeodam.yeodambe.user.security.jwt.ApiAuthenticationEntryPoint;
import com.yeodam.yeodambe.user.security.jwt.CookieAccessTokenResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MetricsSecuritySupport.Endpoints.class)
@ContextConfiguration(classes = MetricsSecuritySupport.Endpoints.class)
@Import({SecurityConfig.class, CookieAccessTokenResolver.class,
        ApiAuthenticationEntryPoint.class, CsrfAccessDeniedHandler.class})
abstract class MetricsSecuritySupport {
    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RedisCsrfTokenRepository csrfTokenRepository;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void configureDeferredCsrfToken() {
        given(csrfTokenRepository.loadDeferredToken(any(), any()))
                .willAnswer(invocation -> new HttpSessionCsrfTokenRepository()
                        .loadDeferredToken(invocation.getArgument(0), invocation.getArgument(1)));
    }

    @Test
    void 헬스_체크는_인증_없이_조회할_수_있다() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @RestController
    static class Endpoints {
        @GetMapping({"/actuator/health", "/actuator/prometheus"})
        String endpoint() {
            return "available";
        }
    }
}

@ActiveProfiles({"local", "test"})
class LocalMetricsSecurityTest extends MetricsSecuritySupport {
    @Test
    void 로컬_메트릭은_익명_요청을_허용한다() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }
}

@ActiveProfiles({"prod", "test"})
class ProductionMetricsSecurityTest extends MetricsSecuritySupport {
    @Test
    void 운영_메트릭은_익명_요청을_거부한다() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    }

    @Test
    void 운영_메트릭은_인증된_회원의_요청도_거부한다() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").with(jwt()))
                .andExpect(status().isForbidden());
    }
}

@ActiveProfiles({"local", "prod", "test"})
class MixedProfilesMetricsSecurityTest extends MetricsSecuritySupport {
    @Test
    void 운영_프로필이_로컬_프로필보다_우선한다() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").with(jwt()))
                .andExpect(status().isForbidden());
    }
}
