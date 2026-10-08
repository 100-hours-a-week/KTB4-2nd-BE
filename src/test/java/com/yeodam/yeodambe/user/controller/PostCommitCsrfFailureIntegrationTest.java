package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.common.exception.CsrfStoreUnavailableException;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.oauth.LoginTicketStore;
import com.yeodam.yeodambe.user.security.oauth.ProfileTokenStore;
import com.yeodam.yeodambe.user.security.session.LoginSessionIssuer;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import com.yeodam.yeodambe.user.service.UserRegistrationService;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PostCommitCsrfFailureIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ProfileTokenStore profiles;
    @Autowired private LoginTicketStore tickets;
    @Autowired private UserRepository users;
    @Autowired private UserRegistrationService registration;
    @Autowired private LoginSessionIssuer issuer;
    @Autowired private LoginSessionStore sessions;
    @Autowired private AccessTokenIssuer jwt;
    @Autowired private StringRedisTemplate redis;
    @MockitoSpyBean private CsrfTokenStore csrf;

    @Test
    void signupCsrfCleanupFailureReturns503WithoutCookiesButKeepsCommittedMemberAndSession() throws Exception {
        String token = UUID.randomUUID().toString();
        String email = token + "@yeodam.test";
        profiles.save(token, new KakaoUserIdentity(token, email));
        failCsrfCleanup(token);
        var response = mvc.perform(post("/users/me/profile")
                        .cookie(new Cookie("profileToken", token), new Cookie("CSRF_CONTEXT", token))
                        .header("X-CSRF-TOKEN", "csrf").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"여행자\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("AUTH_STORE_UNAVAILABLE"))
                .andReturn().getResponse();
        var member = users.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        assertThat(redis.opsForZSet().zCard("yeodam:test:auth:user-sessions:" + member.getUserId())).isEqualTo(1L);
        assertThat(profiles.find(token)).isEmpty();
    }

    @Test
    void loginCsrfCleanupFailureKeepsCommittedSessionWithoutDeliveringCookies() throws Exception {
        String token = UUID.randomUUID().toString();
        String email = token + "@yeodam.test";
        var member = registration.register(email, "여행자", OAuthProvider.KAKAO, token);
        tickets.save(token, new KakaoUserIdentity(token, email), "browser");
        failCsrfCleanup(token);
        var response = mvc.perform(post("/auth/token/exchange")
                        .cookie(new Cookie("OAUTH_BROWSER_CONTEXT", "browser"), new Cookie("CSRF_CONTEXT", token))
                        .header("X-CSRF-TOKEN", "csrf").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginTicket\":\"" + token + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("AUTH_STORE_UNAVAILABLE"))
                .andReturn().getResponse();
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
        assertThat(redis.opsForZSet().zCard("yeodam:test:auth:user-sessions:" + member.getUserId())).isEqualTo(1L);
        assertThat(tickets.consume(token, "browser")).isEmpty();
    }

    @Test
    void logoutCsrfCleanupFailureDoesNotRestoreDeletedSession() throws Exception {
        String unique = UUID.randomUUID().toString();
        var member = registration.register(unique + "@yeodam.test", "여행자", OAuthProvider.KAKAO, unique);
        var session = issuer.issue(member.getUserId());
        failCsrfCleanup(unique);
        var response = mvc.perform(post("/auth/logout")
                        .cookie(new Cookie("accessToken", jwt.issue(member.getUserId(), session.sid())),
                                new Cookie("CSRF_CONTEXT", unique))
                        .header("X-CSRF-TOKEN", "csrf"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("AUTH_STORE_UNAVAILABLE"))
                .andReturn().getResponse();
        assertThat(sessions.findBySid(session.sid())).isEmpty();
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    private void failCsrfCleanup(String context) {
        csrf.save(context, "csrf");
        doThrow(new CsrfStoreUnavailableException()).when(csrf).delete(context);
    }
}
