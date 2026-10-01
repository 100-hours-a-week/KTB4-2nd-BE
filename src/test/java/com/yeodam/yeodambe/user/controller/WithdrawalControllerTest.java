package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.user.exception.WithdrawalFailedException;
import com.yeodam.yeodambe.user.security.CookiePathResolver;
import com.yeodam.yeodambe.user.service.WithdrawalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class WithdrawalControllerTest {

    @Mock
    private WithdrawalService service;

    @Test
    void expiresSecureAuthenticationCookiesAfterSuccessfulWithdrawal() {
        var controller = new WithdrawalController(service, new CookiePathResolver("/api"), true);
        Jwt jwt = mock(Jwt.class);
        given(jwt.getSubject()).willReturn("42");

        var response = controller.withdraw(jwt, "withdrawal-browser");

        then(service).should().withdraw(42L, "withdrawal-browser");
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().get("Set-Cookie"))
                .hasSize(2)
                .anySatisfy(cookie -> assertThat(cookie)
                        .contains("accessToken=;", "Path=/;", "Max-Age=0", "Secure", "HttpOnly", "SameSite=Lax"))
                .anySatisfy(cookie -> assertThat(cookie)
                        .contains("refreshToken=;", "Path=/api/auth;", "Max-Age=0", "Secure", "HttpOnly", "SameSite=Lax"));
    }

    @Test
    void propagatesWithdrawalFailureInsteadOfReturningSuccessfulResponse() {
        var controller = new WithdrawalController(service, new CookiePathResolver("/api"), false);
        Jwt jwt = mock(Jwt.class);
        given(jwt.getSubject()).willReturn("42");
        doThrow(new WithdrawalFailedException()).when(service).withdraw(42L, "withdrawal-browser");

        assertThatThrownBy(() -> controller.withdraw(jwt, "withdrawal-browser"))
                .isInstanceOf(WithdrawalFailedException.class);
    }
}
