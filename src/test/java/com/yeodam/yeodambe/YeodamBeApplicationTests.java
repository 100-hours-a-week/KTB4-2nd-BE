package com.yeodam.yeodambe;

import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.security.TokenHasher;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.oauth.LoginTicketStore;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import com.yeodam.yeodambe.user.service.UserRegistrationService;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class YeodamBeApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CsrfTokenStore csrfTokenStore;

    @Autowired
    private LoginTicketStore loginTicketStore;

    @Autowired
    private UserRegistrationService registrationService;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private LoginSessionStore loginSessionStore;

    @Autowired
    private TokenHasher tokenHasher;

    @Test
    void contextLoads() {
    }

    @Test
    void healthCheckIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void csrfTokenCanBeIssuedWithoutAuthentication() throws Exception {
        MvcResult result = mockMvc.perform(get("/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Cache-Control",
                        "no-store"
                ))
                .andExpect(jsonPath("$.message")
                        .value("CSRF_TOKEN_ISSUED"))
                .andExpect(jsonPath("$.data.headerName")
                        .value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.data.token")
                        .isNotEmpty())
                .andReturn();

        Cookie contextCookie = result.getResponse().getCookie("CSRF_CONTEXT");
        assertThat(contextCookie).isNotNull();
        assertThat(contextCookie.getPath()).isEqualTo("/");
        assertThat(contextCookie.isHttpOnly()).isTrue();
        assertThat(result.getResponse().getHeader("Set-Cookie"))
                .contains("SameSite=Lax")
                .contains("Max-Age=604800");
    }

    @Test
    void kakaoLoginStartIsPublic() throws Exception {
        mockMvc.perform(get("/auth/kakao/authorize"))
                .andExpect(status().isFound())
                .andExpect(header().exists("Location"));
    }

    @Test
    void tokenExchangeRejectsMissingCsrfToken() throws Exception {
        mockMvc.perform(post("/auth/token/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginTicket\":\"test-ticket\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void tokenExchangeWithValidCsrfReachesTicketValidation() throws Exception {
        csrfTokenStore.save("exchange-browser", "known-token");

        mockMvc.perform(post("/auth/token/exchange")
                        .cookie(new Cookie("CSRF_CONTEXT", "exchange-browser"))
                        .cookie(new Cookie("OAUTH_BROWSER_CONTEXT", "exchange-browser"))
                        .header("X-CSRF-TOKEN", "known-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginTicket\":\"test-ticket\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("LOGIN_TICKET_INVALID_OR_EXPIRED"));
    }

    @Test
    void tokenExchangeRejectsMissingBody() throws Exception {
        csrfTokenStore.save("missing-body-browser", "known-token");

        mockMvc.perform(post("/auth/token/exchange")
                        .cookie(new Cookie("CSRF_CONTEXT", "missing-body-browser"))
                        .header("X-CSRF-TOKEN", "known-token")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("LOGIN_TICKET_INVALID_OR_EXPIRED"));
    }

    @Test
    void tokenExchangeRejectsBlankTicket() throws Exception {
        csrfTokenStore.save("blank-ticket-browser", "known-token");

        mockMvc.perform(post("/auth/token/exchange")
                        .cookie(new Cookie("CSRF_CONTEXT", "blank-ticket-browser"))
                        .header("X-CSRF-TOKEN", "known-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginTicket\":\" \"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("LOGIN_TICKET_INVALID_OR_EXPIRED"));
    }

    @Test
    void tokenExchangeRejectsMalformedJson() throws Exception {
        csrfTokenStore.save("malformed-body-browser", "known-token");

        mockMvc.perform(post("/auth/token/exchange")
                        .cookie(new Cookie("CSRF_CONTEXT", "malformed-body-browser"))
                        .header("X-CSRF-TOKEN", "known-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginTicket\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_REQUEST"));
    }

    @Test
    void existingMemberExchangesTicketForWorkingLoginSession() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String providerUserId = "kakao-" + suffix;
        String email = "member-" + suffix + "@yeodam.test";
        String browserContext = "browser-" + suffix;
        String ticket = "ticket-" + suffix;
        User user = registrationService.register(email, "여행자", OAuthProvider.KAKAO, providerUserId);
        loginTicketStore.save(ticket, new KakaoUserIdentity(providerUserId, email), browserContext);
        csrfTokenStore.save(browserContext, "known-token");

        MvcResult result = mockMvc.perform(post("/auth/token/exchange")
                        .cookie(new Cookie("CSRF_CONTEXT", browserContext))
                        .cookie(new Cookie("OAUTH_BROWSER_CONTEXT", browserContext))
                        .header("X-CSRF-TOKEN", "known-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginTicket\":\"" + ticket + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("LOGIN_SUCCESS"))
                .andExpect(jsonPath("$.data.user.userId").value(user.getUserId()))
                .andReturn();

        Cookie accessCookie = result.getResponse().getCookie("accessToken");
        Cookie refreshCookie = result.getResponse().getCookie("refreshToken");
        assertThat(accessCookie).isNotNull();
        assertThat(refreshCookie).isNotNull();

        Jwt jwt = jwtDecoder.decode(accessCookie.getValue());
        String sid = jwt.getClaimAsString("sid");
        assertThat(jwt.getSubject()).isEqualTo(user.getUserId().toString());
        assertThat(loginSessionStore.findBySid(sid).orElseThrow().userId())
                .isEqualTo(user.getUserId());
        assertThat(loginSessionStore.findSidByRefreshTokenHash(
                tokenHasher.hash(refreshCookie.getValue())))
                .contains(sid);
    }

}
