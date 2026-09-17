package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.entity.OAuthAccount;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.exception.LoginTicketInvalidOrExpiredException;
import com.yeodam.yeodambe.user.repository.OAuthAccountRepository;
import com.yeodam.yeodambe.user.security.LoginTicketStore;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LoginTicketExchangeService {

    private final LoginTicketStore loginTicketStore;
    private final OAuthAccountRepository oauthAccountRepository;

    @Transactional(readOnly = true)
    public LoginExchangeDecision exchange(String loginTicket, String browserContext) {
        KakaoUserIdentity identity = loginTicketStore
                .consume(loginTicket, browserContext)
                .orElseThrow(LoginTicketInvalidOrExpiredException::new);

        Optional<OAuthAccount> account = oauthAccountRepository
                .findByProviderAndProviderUserIdAndDeletedAtIsNull(
                        OAuthProvider.KAKAO,
                        identity.providerUserId()
                );

        if (account.isEmpty()) {
            return new LoginExchangeDecision.Onboarding(identity);
        }

        User user = account.get().getUser();
        return new LoginExchangeDecision.ExistingMember(
                user.getUserId(),
                user.getEmail(),
                user.getNickname()
        );
    }
}
