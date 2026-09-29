package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.trip.service.TripWithdrawalService;
import com.yeodam.yeodambe.user.entity.Consent;
import com.yeodam.yeodambe.user.entity.OAuthAccount;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.exception.WithdrawalFailedException;
import com.yeodam.yeodambe.user.repository.ConsentRepository;
import com.yeodam.yeodambe.user.repository.LoginSessionRepository;
import com.yeodam.yeodambe.user.repository.OAuthAccountRepository;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class WithdrawalServiceTest {

    private static final Long USER_ID = 42L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private OAuthAccountRepository oauthAccountRepository;
    @Mock
    private ConsentRepository consentRepository;
    @Mock
    private UserStatsRepository userStatsRepository;
    @Mock
    private LoginSessionRepository loginSessionRepository;
    @Mock
    private TripWithdrawalService tripWithdrawalService;

    @InjectMocks
    private WithdrawalService service;

    @Test
    void withdrawsMemberDataTripsAndAllLoginSessions() {
        User user = org.mockito.Mockito.mock(User.class);
        OAuthAccount oauthAccount = org.mockito.Mockito.mock(OAuthAccount.class);
        Consent consent = org.mockito.Mockito.mock(Consent.class);
        UserStats userStats = org.mockito.Mockito.mock(UserStats.class);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(user.getDeletedAt()).willReturn(null);
        given(oauthAccountRepository.findByUser_UserIdAndDeletedAtIsNull(USER_ID))
                .willReturn(Optional.of(oauthAccount));
        given(consentRepository.findByUser_UserIdAndDeletedAtIsNull(USER_ID))
                .willReturn(Optional.of(consent));
        given(userStatsRepository.findByUser_UserIdAndDeletedAtIsNull(USER_ID))
                .willReturn(Optional.of(userStats));

        service.withdraw(USER_ID);

        ArgumentCaptor<LocalDateTime> withdrawnAt =
                ArgumentCaptor.forClass(LocalDateTime.class);
        then(tripWithdrawalService).should().withdrawAll(eq(USER_ID), withdrawnAt.capture());
        then(oauthAccount).should().withdraw(withdrawnAt.getValue());
        then(consent).should().withdraw(withdrawnAt.getValue());
        then(userStats).should().withdraw(withdrawnAt.getValue());
        then(user).should().withdraw(withdrawnAt.getValue());
        then(loginSessionRepository).should().deleteByUser_UserId(USER_ID);
    }

    @Test
    void failsBeforeDeletingTripsWhenActiveConsentIsMissing() {
        User user = org.mockito.Mockito.mock(User.class);
        OAuthAccount oauthAccount = org.mockito.Mockito.mock(OAuthAccount.class);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
        given(user.getDeletedAt()).willReturn(null);
        given(oauthAccountRepository.findByUser_UserIdAndDeletedAtIsNull(USER_ID))
                .willReturn(Optional.of(oauthAccount));
        given(consentRepository.findByUser_UserIdAndDeletedAtIsNull(USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(USER_ID))
                .isInstanceOf(WithdrawalFailedException.class);

        then(oauthAccount).should(never()).withdraw(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(tripWithdrawalService, loginSessionRepository);
    }
}
