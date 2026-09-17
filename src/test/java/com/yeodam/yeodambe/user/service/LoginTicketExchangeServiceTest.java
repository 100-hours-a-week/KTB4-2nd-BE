package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.exception.LoginTicketInvalidOrExpiredException;
import com.yeodam.yeodambe.user.security.LoginTicketStore;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class LoginTicketExchangeServiceTest {

    @Mock
    private LoginTicketStore loginTicketStore;

    private LoginTicketExchangeService service;

    @BeforeEach
    void setUp() {
        service = new LoginTicketExchangeService(loginTicketStore);
    }

    @Test
    void rejectsExpiredOrAlreadyConsumedTicket() {
        given(loginTicketStore.consume("expired-ticket", "browser-1"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.exchange("expired-ticket", "browser-1"))
                .isInstanceOf(LoginTicketInvalidOrExpiredException.class);

        then(loginTicketStore).should()
                .consume("expired-ticket", "browser-1");
    }

    @Test
    void returnsIdentityForValidTicket() {
        KakaoUserIdentity identity =
                new KakaoUserIdentity("kakao-user-1", "user@example.com");
        given(loginTicketStore.consume("valid-ticket", "browser-1"))
                .willReturn(Optional.of(identity));

        KakaoUserIdentity result = service.exchange("valid-ticket", "browser-1");

        assertThat(result).isEqualTo(identity);
        then(loginTicketStore).should()
                .consume("valid-ticket", "browser-1");
    }
}
