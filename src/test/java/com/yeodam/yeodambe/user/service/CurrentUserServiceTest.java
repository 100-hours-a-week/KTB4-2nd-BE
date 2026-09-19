package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.entity.OAuthAccount;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.exception.UserNotFoundException;
import com.yeodam.yeodambe.user.repository.OAuthAccountRepository;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.service.response.CurrentUserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CurrentUserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OAuthAccountRepository oauthAccountRepository;

    private CurrentUserService service;

    @BeforeEach
    void setUp() {
        service = new CurrentUserService(
                userRepository,
                oauthAccountRepository
        );
    }

    @Test
    void returnsActiveUserAndOAuthConnection() {
        User user = mock(User.class);
        OAuthAccount oauthAccount = mock(OAuthAccount.class);
        given(userRepository.findById(42L))
                .willReturn(Optional.of(user));
        given(user.getDeletedAt()).willReturn(null);
        given(user.getUserId()).willReturn(42L);
        given(user.getEmail()).willReturn("user@example.com");
        given(user.getNickname()).willReturn("여행자");
        given(oauthAccountRepository
                .findByUser_UserIdAndDeletedAtIsNull(42L))
                .willReturn(Optional.of(oauthAccount));
        given(oauthAccount.getProvider()).willReturn(OAuthProvider.KAKAO);

        CurrentUserResponse result = service.find(42L);

        assertThat(result).isEqualTo(new CurrentUserResponse(
                42L,
                "user@example.com",
                "여행자",
                "KAKAO",
                true
        ));
    }

    @Test
    void rejectsDeletedUserBeforeReadingOAuthConnection() {
        User user = mock(User.class);
        given(userRepository.findById(42L))
                .willReturn(Optional.of(user));
        given(user.getDeletedAt()).willReturn(LocalDateTime.now());

        assertThatThrownBy(() -> service.find(42L))
                .isInstanceOf(UserNotFoundException.class);

        verifyNoInteractions(oauthAccountRepository);
    }
}
