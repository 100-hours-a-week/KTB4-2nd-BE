package com.yeodam.yeodambe.user.security.jwt;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CookieJwtAuthenticationIntegrationTest.ProbeController.class})
class CookieJwtAuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRegistrationService userRegistrationService;
    @Autowired
    private LoginSessionIssuer loginSessionIssuer;
    @Autowired
    private AccessTokenIssuer accessTokenIssuer;

    @RestController
    static class ProbeController {
        @GetMapping("/test/auth-probe")
        String probe(@AuthenticationPrincipal Jwt jwt) {
            return jwt.getSubject();
        }
    }

    @Test
    void 유효한_액세스_쿠키로_보호된_요청을_인증한다() throws Exception {
        String unique = UUID.randomUUID().toString();
        User user = userRegistrationService.register(
                "auth-" + unique + "@yeodam.test",
                "인증회원",
                OAuthProvider.KAKAO,
                "kakao-auth-" + unique
        );
        IssuedLoginSession session = loginSessionIssuer.issue(user.getUserId());
        String accessToken = accessTokenIssuer.issue(user.getUserId(), session.sid());

        mockMvc.perform(get("/test/auth-probe")
                        .cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isOk())
                .andExpect(content().string(user.getUserId().toString()));
    }

    @Test
    void 새_회원의_통계를_실제_로그인_쿠키로_조회한다() throws Exception {
        String unique = UUID.randomUUID().toString();
        User user = userRegistrationService.register(
                "stats-" + unique + "@yeodam.test", "통계회원",
                OAuthProvider.KAKAO, "kakao-stats-" + unique);
        IssuedLoginSession session = loginSessionIssuer.issue(user.getUserId());
        String accessToken = accessTokenIssuer.issue(user.getUserId(), session.sid());

        mockMvc.perform(get("/users/me/stats")
                        .cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("USER_STATS_FOUND"))
                .andExpect(jsonPath("$.data.tripCount").value(0))
                .andExpect(jsonPath("$.data.attachmentCount").value(0))
                .andExpect(jsonPath("$.data.storyCount").value(0))
                .andExpect(jsonPath("$.data.storageUsedBytes").value(0))
                .andExpect(jsonPath("$.data.storageMaximumBytes").value(30_000_000_000L));
    }

    @Test
    void 통계_API는_인증_쿠키가_없거나_잘못되면_거부한다() throws Exception {
        mockMvc.perform(get("/users/me/stats"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));
        mockMvc.perform(get("/users/me/stats")
                        .cookie(new Cookie("accessToken", "invalid-jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));
    }

    @Test
    void 액세스_쿠키가_없거나_유효하지_않으면_보호된_요청을_거부한다() throws Exception {
        mockMvc.perform(get("/test/auth-probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("""
                        {
                          "message": "UNAUTHORIZED",
                          "data": null
                        }
                        """));

        mockMvc.perform(get("/test/auth-probe")
                        .cookie(new Cookie("accessToken", "invalid-jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("""
                        {
                          "message": "UNAUTHORIZED",
                          "data": null
                        }
                        """));
    }
}
