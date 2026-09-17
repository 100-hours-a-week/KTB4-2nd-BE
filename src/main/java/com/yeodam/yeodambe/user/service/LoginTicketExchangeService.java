package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.entity.OAuthAccount;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.exception.LoginTicketInvalidOrExpiredException;
import com.yeodam.yeodambe.user.repository.OAuthAccountRepository;
import com.yeodam.yeodambe.user.security.oauth.LoginTicketStore;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.yeodam.yeodambe.user.security.oauth.ProfileTokenGenerator;
import com.yeodam.yeodambe.user.security.oauth.ProfileTokenStore;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LoginTicketExchangeService {

    private final LoginTicketStore loginTicketStore;
    private final OAuthAccountRepository oauthAccountRepository;
    private final ProfileTokenGenerator profileTokenGenerator;
    private final ProfileTokenStore profileTokenStore;

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
            String profileToken = profileTokenGenerator.generate();
            profileTokenStore.save(profileToken, identity);
            return new LoginExchangeDecision.Onboarding(profileToken);
        }

        User user = account.get().getUser();
        return new LoginExchangeDecision.ExistingMember(
                user.getUserId(),
                user.getEmail(),
                user.getNickname()
        );
    }
}
