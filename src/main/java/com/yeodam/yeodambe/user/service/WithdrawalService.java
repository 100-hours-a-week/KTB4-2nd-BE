package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.trip.service.TripWithdrawalService;
import com.yeodam.yeodambe.user.entity.Consent;
import com.yeodam.yeodambe.user.entity.OAuthAccount;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.exception.UserNotFoundException;
import com.yeodam.yeodambe.user.exception.WithdrawalFailedException;
import com.yeodam.yeodambe.user.repository.ConsentRepository;
import com.yeodam.yeodambe.user.repository.LoginSessionRepository;
import com.yeodam.yeodambe.user.repository.OAuthAccountRepository;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final ConsentRepository consentRepository;
    private final UserStatsRepository userStatsRepository;
    private final LoginSessionRepository loginSessionRepository;
    private final TripWithdrawalService tripWithdrawalService;

    @Transactional
    public void withdraw(Long userId) {
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

            loginSessionRepository.deleteByUser_UserId(userId);
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
}