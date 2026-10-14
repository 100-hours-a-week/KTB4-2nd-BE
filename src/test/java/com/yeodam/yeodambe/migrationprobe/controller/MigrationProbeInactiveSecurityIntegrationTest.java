package com.yeodam.yeodambe.migrationprobe.controller;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.migrationprobe.filter.MigrationProbeBodyLimitFilter;
import com.yeodam.yeodambe.migrationprobe.repository.MigrationProbeRepository;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.session.LoginSessionIssuer;
import com.yeodam.yeodambe.user.service.UserRegistrationService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MigrationProbeInactiveSecurityIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ApplicationContext application;
    @Autowired
    private UserRegistrationService users;
    @Autowired
    private LoginSessionIssuer sessions;
    @Autowired
    private AccessTokenIssuer tokens;
    @Autowired
    private CsrfTokenStore csrf;

    @Test
    void validAuthenticationAndCsrfStillReturn404WhenProbeIsInactive() throws Exception {
        String id = UUID.randomUUID().toString();
        var user = users.register("inactive-" + id + "@yeodam.test", "시험회원", OAuthProvider.KAKAO, "inactive-" + id);
        var session = sessions.issue(user.getUserId());
        String token = tokens.issue(user.getUserId(), session.sid());
        csrf.save(id, "known-token");

        mvc.perform(post("/api/internal/migration-write-probe")
                        .contextPath("/api")
                        .servletPath("/internal/migration-write-probe")
                        .cookie(new Cookie("accessToken", token), new Cookie("CSRF_CONTEXT", id))
                        .header("X-CSRF-TOKEN", "known-token")
                        .content(" ".repeat(2048)))
                .andExpect(status().isNotFound());
        assertThat(application.getBeansOfType(MigrationProbeRepository.class)).isEmpty();
        assertThat(application.getBeansOfType(MigrationProbeBodyLimitFilter.class)).isEmpty();
    }
}
