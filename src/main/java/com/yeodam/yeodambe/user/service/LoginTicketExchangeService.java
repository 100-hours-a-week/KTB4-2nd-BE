package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.exception.LoginTicketInvalidOrExpiredException;
import com.yeodam.yeodambe.user.security.LoginTicketStore;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LoginTicketExchangeService {

    private final LoginTicketStore loginTicketStore;

    public KakaoUserIdentity exchange(String loginTicket, String browserContext) {
        return loginTicketStore.consume(loginTicket, browserContext)
                .orElseThrow(LoginTicketInvalidOrExpiredException::new);
    }
}
