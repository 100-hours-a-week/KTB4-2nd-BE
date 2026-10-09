package com.yeodam.yeodambe.search.controller;

import com.yeodam.yeodambe.search.service.SearchService;
import com.yeodam.yeodambe.search.service.response.SearchResponse;
import com.yeodam.yeodambe.common.exception.AiQueryUnavailableException;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.security.SecurityConfig;
import com.yeodam.yeodambe.user.security.csrf.*;
import com.yeodam.yeodambe.user.security.jwt.*;
import com.yeodam.yeodambe.user.security.session.LoginSession;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SearchController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtConfig.class, AccessTokenIssuer.class,
        CookieAccessTokenResolver.class, ApiAuthenticationEntryPoint.class,
        CsrfAccessDeniedHandler.class, RedisCsrfTokenRepository.class, ActiveLoginSessionValidator.class})
class SearchSecurityIntegrationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private AccessTokenIssuer tokens;
    @Autowired
    private JwtEncoder encoder;
    @Autowired
    private JwtProperties jwtProperties;
    @MockitoBean
    private SearchService service;
    @MockitoBean
    private LoginSessionStore sessions;
    @MockitoBean
    private UserRepository users;
    @MockitoBean
    private CsrfTokenStore csrfTokenStore;
    @MockitoBean
    private CsrfTokenGenerator csrfTokenGenerator;

    @BeforeEach
    void setUp() {
        when(sessions.findBySid("sid-42")).thenReturn(Optional.of(new LoginSession(42L, "hash")));
        when(users.findById(42L)).thenReturn(Optional.of(new User("search@test.com", "검색")));
        when(service.search(eq(42L), anyString())).thenAnswer(invocation ->
                new SearchResponse(invocation.getArgument(1), null, null, List.of(), List.of()));
    }

    @Test
    void 검색어를_정규화하고_인증된_사용자_ID로_검색한다() throws Exception {
        mvc.perform(get("/search").param("query", "  제주 바다  ").param("userId", "99")
                        .cookie(cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("SEARCH_SUCCESS"))
                .andExpect(jsonPath("$.data.query").value("제주 바다"))
                .andExpect(jsonPath("$.data.attachments").isEmpty())
                .andExpect(jsonPath("$.data.folders").isEmpty())
                .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.answerError").value(org.hamcrest.Matchers.nullValue()));
        verify(service).search(42L, "제주 바다");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "가", "바다!", "제주\t사진", "바다\n사진", "ㄱㄴ", "éé"})
    void 유효하지_않은_검색어에_검색_전용_오류를_반환한다(String query) throws Exception {
        mvc.perform(get("/search").param("query", query).cookie(cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_SEARCH_QUERY"));
        verifyNoInteractions(service);
    }

    @Test
    void 누락되거나_너무_긴_검색어를_거부하고_정규화한_길이_경계값을_허용한다() throws Exception {
        mvc.perform(get("/search").cookie(cookie())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_SEARCH_QUERY"));
        mvc.perform(get("/search").param("query", "가".repeat(101)).cookie(cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_SEARCH_QUERY"));
        for (String query : List.of("가나", "  " + "a".repeat(100) + "  ", "제주 Trip 123")) {
            mvc.perform(get("/search").param("query", query).cookie(cookie())).andExpect(status().isOk());
        }
    }

    @Test
    void 미인증과_만료된_토큰과_잘못된_서명과_클레임을_거부한다() throws Exception {
        mvc.perform(get("/search").param("query", "바다 사진"))
                .andExpect(status().isUnauthorized());
        String valid = tokens.issue(42L, "sid-42");
        String[] parts = valid.split("\\.");
        String forged = parts[0] + "." + parts[1] + "." + "a".repeat(parts[2].length());
        for (String token : List.of(forged,
                token(jwtProperties.issuer(), jwtProperties.audience(), Instant.now().minusSeconds(120)),
                token("wrong-issuer", jwtProperties.audience(), Instant.now().plusSeconds(300)),
                token(jwtProperties.issuer(), "wrong-audience", Instant.now().plusSeconds(300)))) {
            mvc.perform(get("/search").param("query", "바다 사진")
                            .cookie(new Cookie("accessToken", token)))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service);
    }

    @Test
    void 비활성_세션과_탈퇴한_사용자를_거부한다() throws Exception {
        when(sessions.findBySid("sid-42")).thenReturn(Optional.empty());
        mvc.perform(get("/search").param("query", "바다 사진").cookie(cookie()))
                .andExpect(status().isUnauthorized());
        when(sessions.findBySid("sid-42")).thenReturn(Optional.of(new LoginSession(42L, "hash")));
        User deleted = new User("deleted@test.com", "탈퇴");
        deleted.withdraw(LocalDateTime.now());
        when(users.findById(42L)).thenReturn(Optional.of(deleted));
        mvc.perform(get("/search").param("query", "바다 사진").cookie(cookie()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void AI_서비스_불가와_내부_실패를_공개_API_오류로_변환한다() throws Exception {
        when(service.search(42L, "바다 사진")).thenThrow(new AiQueryUnavailableException());
        mvc.perform(get("/search").param("query", "바다 사진").cookie(cookie()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("SEARCH_SERVICE_UNAVAILABLE"));
        doThrow(new IllegalStateException()).when(service).search(42L, "바다 사진");
        mvc.perform(get("/search").param("query", "바다 사진").cookie(cookie()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("INTERNAL_SERVER_ERROR"));
    }

    private Cookie cookie() {
        return new Cookie("accessToken", tokens.issue(42L, "sid-42"));
    }

    private String token(String issuer, String audience, Instant expiry) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
                .subject("42").claim("sid", "sid-42")
                .issuedAt(expiry.minusSeconds(300)).expiresAt(expiry).build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
