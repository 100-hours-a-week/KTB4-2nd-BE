package com.yeodam.yeodambe.user.security.session;

import com.yeodam.yeodambe.user.security.TokenHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@Slf4j
@RequiredArgsConstructor
public class LoginSessionIssuer {

    private final SessionIdGenerator sessionIdGenerator;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final LoginSessionStore loginSessionStore;
    private final TokenHasher tokenHasher;

    public IssuedLoginSession issue(Long userId) {
        String sid = sessionIdGenerator.generate();
        String refreshToken = refreshTokenGenerator.generate();
        String refreshTokenHash =
                tokenHasher.hash(refreshToken);

        registerRollbackCleanup(sid, userId, refreshTokenHash);
        loginSessionStore.save(
                sid,
                userId,
                refreshTokenHash
        );

        return new IssuedLoginSession(
                sid,
                refreshToken
        );
    }

    private void registerRollbackCleanup(String sid, Long userId, String hash) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }
                try {
                    loginSessionStore.deleteIfMatches(sid, userId, hash);
                } catch (RuntimeException exception) {
                    // The original failure still propagates; expired keys are the final cleanup boundary.
                    log.atError().addKeyValue("event", "auth_session_rollback_cleanup")
                            .addKeyValue("result", "failure")
                            .addKeyValue("failure_stage", "session_cleanup")
                            .addKeyValue("error_code", "AUTH_STORE_UNAVAILABLE")
                            .log("롤백된 로그인 세션의 Redis 정리에 실패했습니다.");
                }
            }
        });
    }
}
