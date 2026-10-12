package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.user.entity.OAuthAccount;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.common.exception.LoginTicketInvalidOrExpiredException;
import com.yeodam.yeodambe.user.repository.OAuthAccountRepository;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.oauth.LoginTicketStore;
import com.yeodam.yeodambe.user.security.oauth.OAuthTokenClaim;
import com.yeodam.yeodambe.user.security.oauth.ProfileTokenGenerator;
import com.yeodam.yeodambe.user.security.oauth.ProfileTokenStore;
import com.yeodam.yeodambe.user.security.session.IssuedLoginSession;
import com.yeodam.yeodambe.user.security.session.LoginSessionIssuer;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;


@Service
@Slf4j
@RequiredArgsConstructor
public class LoginTicketExchangeService {

    private final LoginTicketStore loginTicketStore;
    private final OAuthAccountRepository oauthAccountRepository;
    private final ProfileTokenGenerator profileTokenGenerator;
    private final ProfileTokenStore profileTokenStore;
    private final LoginSessionIssuer loginSessionIssuer;
    private final AccessTokenIssuer accessTokenIssuer;

    @Transactional
    public LoginExchangeDecision exchange(String loginTicket, String browserContext) {
        long startedAt = System.nanoTime();

        String claimOwner = UUID.randomUUID().toString();
        registerCompletion("ticket_completion",
                () -> loginTicketStore.complete(loginTicket, claimOwner),
                () -> loginTicketStore.release(loginTicket, claimOwner));
        try {
            KakaoUserIdentity identity = loginTicketStore
                    .claim(loginTicket, browserContext, claimOwner)
                    .map(OAuthTokenClaim::identity)
                    .orElseThrow(LoginTicketInvalidOrExpiredException::new);

            Optional<OAuthAccount> account = oauthAccountRepository
                    .findByProviderAndProviderUserIdAndDeletedAtIsNull(
                            OAuthProvider.KAKAO,
                            identity.providerUserId()
                    );

            if (account.isEmpty()) {
                String profileToken = profileTokenGenerator.generate();
                String issuanceOwner = UUID.randomUUID().toString();
                registerCompletion("profile_issuance_cleanup", () -> {},
                        () -> profileTokenStore.deleteIfIssuedBy(profileToken, issuanceOwner));
                profileTokenStore.save(profileToken, identity, issuanceOwner);
                log.atInfo()
                        .addKeyValue("event", "auth_login")
                        .addKeyValue("result", "onboarding_required")
                        .addKeyValue("duration_ms", elapsedMillis(startedAt))
                        .log("신규 회원의 프로필 등록을 기다립니다.");
                return new LoginExchangeDecision.Onboarding(profileToken);
            }

            User user = account.get().getUser();
            user.updateProfileImageUrl(identity.profileImageUrl());
            IssuedLoginSession session = loginSessionIssuer.issue(user.getUserId());
            String accessToken = accessTokenIssuer.issue(user.getUserId(), session.sid());

            log.atInfo()
                    .addKeyValue("event", "auth_login")
                    .addKeyValue("result", "success")
                    .addKeyValue("duration_ms", elapsedMillis(startedAt))
                    .log("기존 회원 로그인을 완료했습니다.");
            return new LoginExchangeDecision.ExistingMember(
                    user.getUserId(),
                    user.getEmail(),
                    user.getNickname(),
                    accessToken,
                    session.refreshToken()
            );
        } catch (LoginTicketInvalidOrExpiredException exception) {
            log.atWarn()
                    .addKeyValue("event", "auth_login")
                    .addKeyValue("result", "failure")
                    .addKeyValue("failure_stage", "ticket_exchange")
                    .addKeyValue("error_code", ErrorMessage.LOGIN_TICKET_INVALID_OR_EXPIRED.name())
                    .addKeyValue("duration_ms", elapsedMillis(startedAt))
                    .setCause(exception)
                    .log("로그인 티켓 교환에 실패했습니다.");
            throw exception;
        } catch (RuntimeException exception) {
            log.atError()
                    .addKeyValue("event", "auth_login")
                    .addKeyValue("result", "failure")
                    .addKeyValue("failure_stage", "ticket_exchange")
                    .addKeyValue("error_code", ErrorMessage.INTERNAL_SERVER_ERROR.name())
                    .addKeyValue("duration_ms", elapsedMillis(startedAt))
                    .log("로그인 티켓 교환 중 서버 오류가 발생했습니다.");
            throw exception;
        }
    }

    private void registerCompletion(String stage, Runnable committed, Runnable rolledBack) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                try {
                    switch (status) {
                        case STATUS_COMMITTED -> committed.run();
                        case STATUS_ROLLED_BACK -> rolledBack.run();
                        default -> logCompletionFailure(stage);
                    }
                } catch (RuntimeException exception) {
                    logCompletionFailure(stage);
                }
            }
        });
    }

    private void logCompletionFailure(String stage) {
        log.atError().addKeyValue("event", "auth_oauth_completion")
                .addKeyValue("result", "failure").addKeyValue("failure_stage", stage)
                .addKeyValue("error_code", "AUTH_STORE_UNAVAILABLE")
                .log("로그인 교환의 Redis 임시 데이터 완료 처리에 실패했습니다.");
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
