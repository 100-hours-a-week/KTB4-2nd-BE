package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.session.IssuedLoginSession;
import com.yeodam.yeodambe.user.security.session.LoginSessionIssuer;
import com.yeodam.yeodambe.user.service.UserRegistrationService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({
        TestcontainersConfiguration.class,
        LogoutIntegrationTest.ProbeController.class
})
class LogoutIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRegistrationService userRegistrationService;

    @Autowired
    private LoginSessionIssuer loginSessionIssuer;

    @Autowired
    private AccessTokenIssuer accessTokenIssuer;

    @Autowired
    private CsrfTokenStore csrfTokenStore;

    @RestController
    static class ProbeController {

        @GetMapping("/test/logout-probe")
        String probe(@AuthenticationPrincipal Jwt jwt) {
            return jwt.getSubject();
        }
    }

    @Test
    void logoutInvalidatesCurrentAccessTokenSession() throws Exception {
        String unique = UUID.randomUUID().toString();
        User user = userRegistrationService.register(
                "logout-" + unique + "@yeodam.test",
                "로그아웃회원",
                OAuthProvider.KAKAO,
                "kakao-logout-" + unique
        );
        IssuedLoginSession session = loginSessionIssuer.issue(
                user.getUserId()
        );
        String accessToken = accessTokenIssuer.issue(
                user.getUserId(),
                session.sid()
        );
        csrfTokenStore.save("logout-browser", "csrf-token");

        mockMvc.perform(post("/auth/logout")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", "logout-browser")
                        )
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/test/logout-probe")
                        .cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("""
                        {
                          "message": "UNAUTHORIZED",
                          "data": null
                        }
                        """));
    }
}
