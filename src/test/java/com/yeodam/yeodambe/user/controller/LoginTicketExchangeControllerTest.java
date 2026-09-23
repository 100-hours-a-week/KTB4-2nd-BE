package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.user.service.LoginExchangeDecision;
import com.yeodam.yeodambe.user.service.LoginTicketExchangeService;
import com.yeodam.yeodambe.user.security.CookiePathResolver;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class LoginTicketExchangeControllerTest {

    @Mock
    private LoginTicketExchangeService service;

    @Mock
    private CsrfTokenStore csrfTokenStore;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new LoginTicketExchangeController(
                service, csrfTokenStore, false, new CookiePathResolver("/api")
        )).build();
    }

    @Test
    void existingMemberReceivesAccessAndRefreshCookies() throws Exception {
        given(service.exchange("ticket-1", "browser-1"))
                .willReturn(new LoginExchangeDecision.ExistingMember(
                        42L, "member@example.com", "여행자", "access-1", "refresh-1"
                ));

        var response = mockMvc.perform(post("/auth/token/exchange")
                        .cookie(
                                new Cookie("OAUTH_BROWSER_CONTEXT", "browser-1"),
                                new Cookie("CSRF_CONTEXT", "csrf-browser-1")
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginTicket\":\"ticket-1\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.message").value("LOGIN_SUCCESS"))
                .andExpect(jsonPath("$.data.expiresIn").value(1800))
                .andExpect(jsonPath("$.data.requiresNickname").value(false))
                .andExpect(jsonPath("$.data.user.userId").value(42))
                .andExpect(jsonPath("$.data.user.email").value("member@example.com"))
                .andExpect(jsonPath("$.data.user.nickname").value("여행자"))
                .andReturn().getResponse();

        assertThat(response.getHeaders("Set-Cookie"))
                .hasSize(2)
                .anySatisfy(cookie -> assertThat(cookie)
                        .contains("accessToken=access-1", "Path=/", "Max-Age=1800", "HttpOnly", "SameSite=Lax"))
                .anySatisfy(cookie -> assertThat(cookie)
                        .contains("refreshToken=refresh-1", "Path=/api/auth", "Max-Age=604800", "HttpOnly", "SameSite=Lax"));
        then(csrfTokenStore).should().delete("csrf-browser-1");
    }

    @Test
    void newMemberReceivesOnlyProfileCookie() throws Exception {
        given(service.exchange("ticket-2", "browser-2"))
                .willReturn(new LoginExchangeDecision.Onboarding("profile-1"));

        var response = mockMvc.perform(post("/auth/token/exchange")
                        .cookie(new Cookie("OAUTH_BROWSER_CONTEXT", "browser-2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginTicket\":\"ticket-2\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.message").value("ONBOARDING_REQUIRED"))
                .andExpect(jsonPath("$.data.expiresIn").value(600))
                .andExpect(jsonPath("$.data.requiresNickname").value(true))
                .andExpect(jsonPath("$.data.user").doesNotExist())
                .andReturn().getResponse();

        assertThat(response.getHeaders("Set-Cookie"))
                .singleElement()
                .asString()
                .contains("profileToken=profile-1", "Path=/api/users/me/profile", "Max-Age=600", "HttpOnly", "SameSite=Lax");
        then(csrfTokenStore).should(never()).delete(org.mockito.ArgumentMatchers.any());
    }
}
