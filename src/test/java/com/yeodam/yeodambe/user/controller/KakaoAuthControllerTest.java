package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.user.service.KakaoLoginStartService;
import com.yeodam.yeodambe.user.service.KakaoLoginCallbackService;
import com.yeodam.yeodambe.user.security.CookiePathResolver;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
class KakaoAuthControllerTest {

    @Mock
    private KakaoLoginStartService loginStartService;

    @Mock
    private KakaoLoginCallbackService loginCallbackService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        KakaoAuthController controller =
                new KakaoAuthController(
                        loginStartService,
                        loginCallbackService,
                        "https://app.yeodam.test/auth/callback",
                        false,
                        new CookiePathResolver("/api")
                );
        mockMvc = standaloneSetup(controller).build();
    }

    @Test
    void redirectsUsingExistingBrowserContext() throws Exception {
        String authorizationUrl =
                "https://kauth.kakao.com/oauth/authorize?state=fixed-state";

        given(loginStartService.start("browser-1"))
                .willReturn(authorizationUrl);

        mockMvc.perform(
                        get("/auth/kakao/authorize")
                                .cookie(new Cookie(
                                        "OAUTH_BROWSER_CONTEXT",
                                        "browser-1"
                                ))
                )
                .andExpect(status().isFound())
                .andExpect(header().string("Location", authorizationUrl))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        then(loginStartService).should()
                .start("browser-1");
    }

    @Test
    void createsBrowserContextCookieWhenMissing() throws Exception {
        String authorizationUrl =
                "https://kauth.kakao.com/oauth/authorize?state=fixed-state";

        given(loginStartService.start(anyString()))
                .willReturn(authorizationUrl);

        MvcResult result = mockMvc.perform(
                        get("/auth/kakao/authorize")
                )
                .andExpect(status().isFound())
                .andExpect(header().string(
                        "Location",
                        authorizationUrl
                ))
                .andReturn();

        ArgumentCaptor<String> browserContextCaptor =
                ArgumentCaptor.forClass(String.class);

        then(loginStartService).should()
                .start(browserContextCaptor.capture());

        String browserContext =
                browserContextCaptor.getValue();

        String setCookie =
                result.getResponse().getHeader("Set-Cookie");

        assertThat(browserContext).isNotBlank();

        assertThat(setCookie)
                .contains(
                        "OAUTH_BROWSER_CONTEXT="
                                + browserContext
                )
                .contains("Path=/api/auth;")
                .doesNotContain("Path=/api/auth/kakao")
                .contains("Max-Age=600")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .doesNotContain("Secure")
                .doesNotContain("Domain=");
    }

    @Test
    void redirectsToFrontendWithLoginTicket() throws Exception {
        given(loginCallbackService.issueLoginTicket(
                "authorization-code",
                "valid-state",
                "browser-1"
        )).willReturn("login-ticket");

        mockMvc.perform(
                        get("/auth/kakao/callback")
                                .param("code", "authorization-code")
                                .param("state", "valid-state")
                                .cookie(new Cookie(
                                        "OAUTH_BROWSER_CONTEXT",
                                        "browser-1"
                                ))
                )
                .andExpect(status().isFound())
                .andExpect(header().string(
                        "Location",
                        "https://app.yeodam.test/auth/callback"
                                + "?loginTicket=login-ticket"
                ))
                .andExpect(header().string(
                        "Cache-Control",
                        "no-store"
                ))
                .andExpect(header().string(
                        "Referrer-Policy",
                        "no-referrer"
                ));

        then(loginCallbackService).should()
                .issueLoginTicket(
                        "authorization-code",
                        "valid-state",
                        "browser-1"
                );
    }
}
