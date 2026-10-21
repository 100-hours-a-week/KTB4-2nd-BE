package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.user.service.AccessTokenRefreshService;
import com.yeodam.yeodambe.user.security.CookiePathResolver;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class AccessTokenRefreshControllerTest {

    @Mock
    private AccessTokenRefreshService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(
                new AccessTokenRefreshController(
                        service, false, new CookiePathResolver("/api")
                )
        ).build();
    }

    @Test
    void refreshesAccessAndRefreshTokenCookies() throws Exception {
        given(service.refresh("old-refresh-token"))
                .willReturn(new AccessTokenRefreshService.Result(
                        "new-access-token",
                        "new-refresh-token"
                ));

        var response = mockMvc.perform(post("/auth/token/refresh")
                        .cookie(new Cookie("refreshToken", "old-refresh-token")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.message").value("TOKEN_REFRESH_SUCCESS"))
                .andExpect(jsonPath("$.data.expiresIn").value(1800))
                .andReturn().getResponse();

        then(service).should().refresh("old-refresh-token");
        assertThat(response.getHeaders("Set-Cookie"))
                .hasSize(2)
                .anySatisfy(cookie -> assertThat(cookie)
                        .contains(
                                "accessToken=new-access-token",
                                "Path=/",
                                "Max-Age=1800",
                                "HttpOnly",
                                "SameSite=Lax"
                        ))
                .anySatisfy(cookie -> assertThat(cookie)
                        .contains(
                                "refreshToken=new-refresh-token",
                                "Path=/api/auth",
                                "Max-Age=604800",
                                "HttpOnly",
                                "SameSite=Lax"
                        ));
    }
    @Test
    void queuedRefreshKeepsExistingResponseAndCookies() throws Exception {
        var gateway = org.mockito.Mockito.mock(com.yeodam.yeodambe.user.migration.MigrationAuthGateway.class);
        var controller = new AccessTokenRefreshController(service, false, new CookiePathResolver("/api"));
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "migrationAuth", gateway);
        org.mockito.Mockito.doAnswer(invocation -> ((java.util.function.Supplier<?>)invocation.getArgument(1)).get()).when(gateway).directOrQueue(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        given(gateway.request("refresh", "old")).willReturn(new com.yeodam.yeodambe.user.migration.MigrationAuthReply(
            "id", "refresh", 200, "new-access", "new-refresh", null));
        standaloneSetup(controller).build().perform(post("/auth/token/refresh").cookie(new Cookie("refreshToken", "old")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.expiresIn").value(1800))
            .andExpect(header().string("Cache-Control", "no-store"));
        then(service).shouldHaveNoInteractions();
    }

}
