package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.entity.OAuthAccount;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.common.exception.UserNotFoundException;
import com.yeodam.yeodambe.user.repository.OAuthAccountRepository;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.service.response.CurrentUserResponse;
import com.yeodam.yeodambe.user.service.response.UserStatsResponse;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import com.yeodam.yeodambe.story.repository.StoryRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CurrentUserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OAuthAccountRepository oauthAccountRepository;

    @Mock
    private UserStatsRepository userStatsRepository;

    @Mock
    private StoryRepository storyRepository;

    private CurrentUserService service;

    @BeforeEach
    void setUp() {
        service = new CurrentUserService(
                userRepository,
                oauthAccountRepository,
                userStatsRepository,
                storyRepository
        );
    }

    @Test
    void 활성_회원과_OAuth_연결_정보를_반환한다() {
        User user = mock(User.class);
        OAuthAccount oauthAccount = mock(OAuthAccount.class);
        given(userRepository.findById(42L))
                .willReturn(Optional.of(user));
        given(user.getDeletedAt()).willReturn(null);
        given(user.getUserId()).willReturn(42L);
        given(user.getEmail()).willReturn("user@example.com");
        given(user.getNickname()).willReturn("여행자");
        given(user.getProfileImageUrl())
                .willReturn("https://k.kakaocdn.net/current-thumbnail.jpg");
        given(oauthAccountRepository
                .findByUser_UserIdAndDeletedAtIsNull(42L))
                .willReturn(Optional.of(oauthAccount));
        given(oauthAccount.getProvider()).willReturn(OAuthProvider.KAKAO);

        CurrentUserResponse result = service.find(42L);

        assertThat(result).isEqualTo(new CurrentUserResponse(
                42L,
                "user@example.com",
                "여행자",
                "https://k.kakaocdn.net/current-thumbnail.jpg",
                "KAKAO",
                true
        ));
    }

    @Test
    void 기존_HTTP_프로필을_DB_변경_없이_HTTPS로_반환한다() {
        User user = mock(User.class);
        given(userRepository.findById(42L)).willReturn(Optional.of(user));
        given(user.getProfileImageUrl()).willReturn("http://k.kakaocdn.net/photo.jpg");
        given(oauthAccountRepository.findByUser_UserIdAndDeletedAtIsNull(42L))
                .willReturn(Optional.empty());

        CurrentUserResponse result = service.find(42L);

        assertThat(result.profileImageUrl()).isEqualTo("https://k.kakaocdn.net/photo.jpg");
        assertThat(user.getProfileImageUrl()).isEqualTo("http://k.kakaocdn.net/photo.jpg");
        verify(user, never()).updateProfileImageUrl(anyString());
    }

    @Test
    void OAuth_연결을_읽기_전에_탈퇴한_사용자를_거부한다() {
        User user = mock(User.class);
        given(userRepository.findById(42L))
                .willReturn(Optional.of(user));
        given(user.getDeletedAt()).willReturn(LocalDateTime.now());

        assertThatThrownBy(() -> service.find(42L))
                .isInstanceOf(UserNotFoundException.class);

        verifyNoInteractions(oauthAccountRepository);
    }

    @Test
    void 저장된_사용량과_현재_스토리_수를_조합하고_예약량은_제외한다() {
        User user = new User("stats@test.example", "회원");
        UserStats stats = new UserStats(user);
        stats.replaceActiveTripUsage(2, 10, 4096);
        stats.reserveStorage(1024);
        given(userRepository.findByUserIdAndDeletedAtIsNull(42L)).willReturn(Optional.of(user));
        given(userStatsRepository.findByUser_UserIdAndDeletedAtIsNull(42L)).willReturn(Optional.of(stats));
        given(storyRepository.countCurrentCompletedByUserId(42L)).willReturn(1L);

        UserStatsResponse result = service.findStats(42L);

        assertThat(result).isEqualTo(new UserStatsResponse(2, 10, 1, 4096, 30_000_000_000L));
        assertThat(stats.getStorageReservedBytes()).isEqualTo(1024);
        assertThat(stats.getStoryCount()).isZero();
        verify(userStatsRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(oauthAccountRepository);
    }

    @Test
    void 자료가_없는_회원은_최대_용량과_영인_통계를_반환한다() {
        User user = new User("empty@test.example", "회원");
        UserStats stats = new UserStats(user);
        given(userRepository.findByUserIdAndDeletedAtIsNull(42L)).willReturn(Optional.of(user));
        given(userStatsRepository.findByUser_UserIdAndDeletedAtIsNull(42L)).willReturn(Optional.of(stats));
        given(storyRepository.countCurrentCompletedByUserId(42L)).willReturn(0L);

        assertThat(service.findStats(42L))
                .isEqualTo(new UserStatsResponse(0, 0, 0, 0, 30_000_000_000L));
    }

    @Test
    void 없는_회원이나_탈퇴한_회원의_통계를_읽지_않는다() {
        given(userRepository.findByUserIdAndDeletedAtIsNull(42L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findStats(42L)).isInstanceOf(UserNotFoundException.class);

        verifyNoInteractions(userStatsRepository, storyRepository);
    }

    @Test
    void 회원_통계_누락을_영인_통계로_숨기지_않는다() {
        given(userRepository.findByUserIdAndDeletedAtIsNull(42L))
                .willReturn(Optional.of(new User("missing@test.example", "회원")));
        given(userStatsRepository.findByUser_UserIdAndDeletedAtIsNull(42L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findStats(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("사용자 통계가 없습니다.");

        verifyNoInteractions(storyRepository);
    }
}
