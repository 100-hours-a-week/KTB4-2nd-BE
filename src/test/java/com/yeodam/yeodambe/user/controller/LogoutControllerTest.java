package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.user.service.LogoutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class LogoutControllerTest {

    @Mock
    private LogoutService service;

    private LogoutController controller;

    @BeforeEach
    void setUp() {
        controller = new LogoutController(service, false);
    }

    @Test
    void deletesCurrentSessionAndExpiresAuthenticationCookies() {
        Jwt jwt = mock(Jwt.class);
        given(jwt.getClaimAsString("sid"))
                .willReturn("current-session-id");

        ResponseEntity<Void> response = controller.logout(jwt);

        then(service).should().logout("current-session-id");
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().get("Set-Cookie"))
                .hasSize(2)
                .anySatisfy(cookie -> assertThat(cookie)
                        .contains(
                                "accessToken=",
                                "Path=/",
                                "Max-Age=0",
                                "HttpOnly",
                                "SameSite=Lax"
                        ))
                .anySatisfy(cookie -> assertThat(cookie)
                        .contains(
                                "refreshToken=",
                                "Path=/auth",
                                "Max-Age=0",
                                "HttpOnly",
                                "SameSite=Lax"
                        ));
    }
}
