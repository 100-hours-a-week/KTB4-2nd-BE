package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.common.exception.WithdrawalFailedException;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.TripWithdrawalService;
import com.yeodam.yeodambe.user.entity.Consent;
import com.yeodam.yeodambe.user.entity.OAuthAccount;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.ConsentRepository;
import com.yeodam.yeodambe.user.repository.OAuthAccountRepository;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class WithdrawalTransactionServiceTest {

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
    private TripWithdrawalService tripWithdrawalService;

    @Mock
    private TripRepository trips;

    @InjectMocks
    private WithdrawalTransactionService service;

    @Test
    void 회원_데이터와_여행을_같은_탈퇴_시각으로_처리한다() {
        User user = Mockito.mock(User.class);
        OAuthAccount oauthAccount = Mockito.mock(OAuthAccount.class);
        Consent consent = Mockito.mock(Consent.class);
        UserStats userStats = Mockito.mock(UserStats.class);
        given(userRepository.findActiveForUpdate(USER_ID)).willReturn(Optional.of(user));
        given(oauthAccountRepository.findByUser_UserIdAndDeletedAtIsNull(USER_ID))
                .willReturn(Optional.of(oauthAccount));
        given(consentRepository.findByUser_UserIdAndDeletedAtIsNull(USER_ID))
                .willReturn(Optional.of(consent));
        given(userStatsRepository.findByUser_UserIdAndDeletedAtIsNull(USER_ID))
                .willReturn(Optional.of(userStats));

        service.withdraw(USER_ID, List.of());

        ArgumentCaptor<LocalDateTime> withdrawnAt =
                ArgumentCaptor.forClass(LocalDateTime.class);
        then(tripWithdrawalService).should().withdrawAll(eq(USER_ID), withdrawnAt.capture());
        then(oauthAccount).should().withdraw(withdrawnAt.getValue());
        then(consent).should().withdraw(withdrawnAt.getValue());
        then(userStats).should().withdraw(withdrawnAt.getValue());
        then(user).should().withdraw(withdrawnAt.getValue());
    }

    @Test
    void 활성_동의가_없으면_여행을_삭제하기_전에_실패한다() {
        User user = Mockito.mock(User.class);
        OAuthAccount oauthAccount = Mockito.mock(OAuthAccount.class);
        given(userRepository.findActiveForUpdate(USER_ID)).willReturn(Optional.of(user));
        given(oauthAccountRepository.findByUser_UserIdAndDeletedAtIsNull(USER_ID))
                .willReturn(Optional.of(oauthAccount));
        given(consentRepository.findByUser_UserIdAndDeletedAtIsNull(USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(USER_ID, List.of()))
                .isInstanceOf(WithdrawalFailedException.class);

        then(oauthAccount).should(never()).withdraw(ArgumentMatchers.any());
        verifyNoInteractions(tripWithdrawalService);
    }
}
