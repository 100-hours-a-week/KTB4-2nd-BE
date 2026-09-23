package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.oauth.ProfileTokenStore;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ProfileRegistrationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProfileTokenStore profileTokenStore;
    @Autowired
    private CsrfTokenStore csrfTokenStore;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void registrationCreatesMemberDataAndReturnsLoginCookies() throws Exception {
        String unique = UUID.randomUUID().toString();
        String email = "onboarding-" + unique + "@yeodam.test";
        String profileToken = "profile-" + unique;
        String csrfContext = "csrf-" + unique;
        profileTokenStore.save(
                profileToken,
                new KakaoUserIdentity("kakao-" + unique, email)
        );
        csrfTokenStore.save(csrfContext, "known-csrf-token");

        var response = mockMvc.perform(post("/users/me/profile")
                        .cookie(
                                new Cookie("profileToken", profileToken),
                                new Cookie("CSRF_CONTEXT", csrfContext)
                        )
                        .header("X-CSRF-TOKEN", "known-csrf-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"여행자\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("ONBOARDING_SUCCESS"))
                .andExpect(jsonPath("$.data.nickname").value("여행자"))
                .andExpect(jsonPath("$.data.expiresIn").value(1800))
                .andReturn().getResponse();

        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE email = ?", Long.class, email
        );
        assertThat(userId).isNotNull();
        assertThat(csrfTokenStore.find(csrfContext)).isNull();
        assertThat(response.getContentAsString()).contains("\"userId\":" + userId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth_accounts WHERE user_id = ?", Long.class, userId
        )).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consents WHERE user_id = ? AND is_agreed = TRUE", Long.class, userId
        )).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_stats WHERE user_id = ?", Long.class, userId
        )).isEqualTo(1L);
        assertThat(profileTokenStore.find(profileToken)).isEmpty();
        assertThat(response.getHeaders("Set-Cookie"))
                .hasSize(3)
                .anySatisfy(cookie -> assertThat(cookie).contains("accessToken="))
                .anySatisfy(cookie -> assertThat(cookie).contains("refreshToken="))
                .anySatisfy(cookie -> assertThat(cookie)
                        .contains("profileToken=", "Max-Age=0"));
    }
}
