package com.yeodam.yeodambe.user.security.csrf;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CsrfFilterIntegrationTest.ProbeController.class})
class CsrfFilterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CsrfTokenStore csrfTokenStore;

    @RestController
    static class ProbeController {
        @PostMapping("/test/csrf-probe")
        ResponseEntity<Void> probe() {
            return ResponseEntity.noContent().build();
        }
    }

    @Test
    void validTokenReachesController() throws Exception {
        csrfTokenStore.save("browser-valid", "known-token");

        mockMvc.perform(post("/test/csrf-probe")
                        .with(user("test"))
                        .cookie(new Cookie("CSRF_CONTEXT", "browser-valid"))
                        .header("X-CSRF-TOKEN", "known-token"))
                .andExpect(status().isNoContent());
    }

    @Test
    void invalidTokenIsRejected() throws Exception {
        csrfTokenStore.save("browser-wrong-token", "known-token");

        mockMvc.perform(post("/test/csrf-probe")
                        .with(user("test"))
                        .cookie(new Cookie("CSRF_CONTEXT", "browser-wrong-token"))
                        .header("X-CSRF-TOKEN", "wrong-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void profileRegistrationWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/users/me/profile")
                        .cookie(new Cookie("profileToken", "unused-profile-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"여행자\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void tokenRefreshWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/auth/token/refresh")
                        .servletPath("/auth/token/refresh")
                        .cookie(
                                new Cookie("accessToken", "expired-access-token"),
                                new Cookie("refreshToken", "unused-refresh-token")
                        ))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void profileRegistrationWithValidCsrfReachesTokenValidation() throws Exception {
        csrfTokenStore.save("profile-browser", "profile-csrf-token");

        mockMvc.perform(post("/users/me/profile")
                        .cookie(
                                new Cookie("CSRF_CONTEXT", "profile-browser"),
                                new Cookie("profileToken", "missing-profile-token")
                        )
                        .header("X-CSRF-TOKEN", "profile-csrf-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"여행자\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value("ONBOARDING_TOKEN_INVALID_OR_EXPIRED"));
    }
}
