package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.trip.service.TripWithdrawalService;
import com.yeodam.yeodambe.user.entity.Consent;
import com.yeodam.yeodambe.user.entity.OAuthAccount;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.exception.UserNotFoundException;
import com.yeodam.yeodambe.user.exception.WithdrawalFailedException;
import com.yeodam.yeodambe.user.repository.ConsentRepository;
import com.yeodam.yeodambe.user.repository.OAuthAccountRepository;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class WithdrawalService {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final ConsentRepository consentRepository;
    private final UserStatsRepository userStatsRepository;
    private final LoginSessionStore loginSessionStore;
    private final TripWithdrawalService tripWithdrawalService;
    private final CsrfTokenStore csrfTokenStore;

    @Transactional
    public void withdraw(Long userId, String csrfContext) {
        try {
            LocalDateTime withdrawnAt = LocalDateTime.now();

            User user = userRepository.findById(userId)
                    .filter(found -> found.getDeletedAt() == null)
                    .orElseThrow(UserNotFoundException::new);

            OAuthAccount oauthAccount = oauthAccountRepository
                    .findByUser_UserIdAndDeletedAtIsNull(userId)
                    .orElseThrow(WithdrawalFailedException::new);

            Consent consent = consentRepository
                    .findByUser_UserIdAndDeletedAtIsNull(userId)
                    .orElseThrow(WithdrawalFailedException::new);

            UserStats userStats = userStatsRepository
                    .findByUser_UserIdAndDeletedAtIsNull(userId)
                    .orElseThrow(WithdrawalFailedException::new);

            tripWithdrawalService.withdrawAll(userId, withdrawnAt);

            oauthAccount.withdraw(withdrawnAt);
            consent.withdraw(withdrawnAt);
            userStats.withdraw(withdrawnAt);
            user.withdraw(withdrawnAt);

            cleanupAfterCommit(userId, csrfContext);
        } catch (
                UserNotFoundException
                | WithdrawalFailedException
                | DataAccessResourceFailureException exception
        ) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new WithdrawalFailedException(exception);
        }
    }

    private void cleanupAfterCommit(Long userId, String csrfContext) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanupAuthenticationData(userId, csrfContext);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanupAuthenticationData(userId, csrfContext);
            }
        });
    }

    private void cleanupAuthenticationData(Long userId, String csrfContext) {
        try {
            csrfTokenStore.delete(csrfContext);
        } catch (RuntimeException exception) {
            logCleanupFailure("csrf_cleanup");
        }
        try {
            loginSessionStore.deleteByUserId(userId);
        } catch (RuntimeException exception) {
            logCleanupFailure("session_cleanup");
        }
    }

    private void logCleanupFailure(String stage) {
        log.atError().addKeyValue("event", "auth_session_withdrawal_cleanup")
                .addKeyValue("result", "failure")
                .addKeyValue("failure_stage", stage)
                .addKeyValue("error_code", "AUTH_STORE_UNAVAILABLE")
                .log("탈퇴 완료 후 Redis 인증 데이터 정리에 실패했습니다.");
    }
}
