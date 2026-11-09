package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.common.exception.UserNotFoundException;
import com.yeodam.yeodambe.common.exception.WithdrawalFailedException;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.TripWithdrawalService;
import com.yeodam.yeodambe.user.entity.Consent;
import com.yeodam.yeodambe.user.entity.OAuthAccount;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.exception.UserInternalErrorMessage;
import com.yeodam.yeodambe.user.repository.ConsentRepository;
import com.yeodam.yeodambe.user.repository.OAuthAccountRepository;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WithdrawalTransactionService {
    private final UserRepository userRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final ConsentRepository consentRepository;
    private final UserStatsRepository userStatsRepository;
    private final TripWithdrawalService tripWithdrawalService;
    private final TripRepository trips;

    @Transactional
    public void withdraw(Long userId, List<Long> expectedTripIds) {
        try {
            LocalDateTime withdrawnAt = LocalDateTime.now();

            User user = userRepository.findActiveForUpdate(userId)
                    .orElseThrow(UserNotFoundException::new);

            if (!trips.findActiveOwnedIds(userId).equals(expectedTripIds)) {
                throw new OptimisticLockingFailureException(
                        UserInternalErrorMessage.WITHDRAWAL_TRIP_SET_CHANGED.message());
            }

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

        } catch (
                UserNotFoundException
                | WithdrawalFailedException
                | DataAccessResourceFailureException
                | OptimisticLockingFailureException exception
        ) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new WithdrawalFailedException(exception);
        }
    }

}
