package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.common.exception.WithdrawalFailedException;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.TripEditLock;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class WithdrawalService {

    private final TripRepository trips;
    private final TripEditLock editLock;
    private final WithdrawalTransactionService transactions;
    private final LoginSessionStore loginSessionStore;
    private final CsrfTokenStore csrfTokenStore;

    public void withdraw(Long userId, String csrfContext) {
        boolean committed = false;
        for (int attempt = 0; attempt < 3 && !committed; attempt++) {
            List<Long> tripIds = findActiveOwnedTripIds(userId);
            try {
                editLock.executeAll(tripIds, () -> {
                    transactions.withdraw(userId, tripIds);
                    return null;
                });
                committed = true;
            } catch (OptimisticLockingFailureException changedTrips) {
                if (attempt == 2) {
                    throw changedTrips;
                }
            }
        }
        cleanupAuthenticationData(userId, csrfContext);
    }

    private List<Long> findActiveOwnedTripIds(Long userId) {
        try {
            return trips.findActiveOwnedIds(userId);
        } catch (DataAccessResourceFailureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new WithdrawalFailedException(exception);
        }
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
