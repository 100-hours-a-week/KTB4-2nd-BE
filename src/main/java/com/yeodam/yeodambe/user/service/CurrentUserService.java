package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.entity.OAuthAccount;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.exception.UserNotFoundException;
import com.yeodam.yeodambe.user.repository.OAuthAccountRepository;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.service.response.CurrentUserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oauthAccountRepository;

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
                user.getProfileImageUrl(),
                oauthProvider,
                oauthAccount.isPresent()
        );
    }
}