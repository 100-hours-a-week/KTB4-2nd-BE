package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.entity.OAuthAccount;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.common.exception.UserNotFoundException;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.exception.UserInternalErrorMessage;
import com.yeodam.yeodambe.user.repository.OAuthAccountRepository;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import com.yeodam.yeodambe.user.service.response.CurrentUserResponse;
import com.yeodam.yeodambe.user.service.response.UserStatsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final UserStatsRepository userStatsRepository;
    private final StoryRepository storyRepository;

    @Transactional(readOnly = true)
    public CurrentUserResponse find(Long userId) {
        User user = userRepository.findById(userId)
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(UserNotFoundException::new);

        Optional<OAuthAccount> oauthAccount =
                oauthAccountRepository
                        .findByUser_UserIdAndDeletedAtIsNull(userId);

        String oauthProvider = oauthAccount
                .map(account -> account.getProvider().name())
                .orElse(null);

        return new CurrentUserResponse(
                user.getUserId(),
                user.getEmail(),
                user.getNickname(),
                KakaoProfileImageUrl.toHttps(user.getProfileImageUrl()),
                oauthProvider,
                oauthAccount.isPresent()
        );
    }
    @Transactional(readOnly = true)
    public UserStatsResponse findStats(Long userId) {
        userRepository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(UserNotFoundException::new);

        UserStats stats = userStatsRepository
                .findByUser_UserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new IllegalStateException(
                        UserInternalErrorMessage.USER_STATS_MISSING.message()
                ));

        long storyCount = storyRepository
                .countCurrentCompletedByUserId(userId);

        return new UserStatsResponse(
                stats.getTripCount(),
                stats.getAttachmentCount(),
                storyCount,
                stats.getStorageUsedBytes(),
                stats.getStorageMaximumBytes()
        );
    }
}
