package com.yeodam.yeodambe.user.service;

import java.time.LocalDateTime;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.OAuthAccount;
import com.yeodam.yeodambe.user.entity.Consent;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.exception.DuplicateEmailException;
import com.yeodam.yeodambe.user.exception.DuplicateOAuthAccountException;
import com.yeodam.yeodambe.user.exception.InvalidNicknameException;
import com.yeodam.yeodambe.user.repository.ConsentRepository;
import com.yeodam.yeodambe.user.repository.OAuthAccountRepository;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;



@Service
@RequiredArgsConstructor
public class UserRegistrationService {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final ConsentRepository consentRepository;
    private final UserStatsRepository userStatsRepository;

    @Transactional
    public User register(
            String email,
            String nickname,
            OAuthProvider provider,
            String providerUserId
    ) {
        if (nickname == null ||
                !nickname.matches("^[가-힣A-Za-z0-9]{2,10}$")) {
            throw new InvalidNicknameException();
        }
        if (userRepository.existsByEmailAndDeletedAtIsNull(email))
        {
            throw new DuplicateEmailException();
        }
        if(oauthAccountRepository.existsByProviderAndProviderUserIdAndDeletedAtIsNull(provider,providerUserId))
        {
            throw new DuplicateOAuthAccountException();
        }
        User user = userRepository.save(new User(email, nickname));

        oauthAccountRepository.save(new OAuthAccount(user, provider, providerUserId));

        consentRepository.save(new Consent(user, LocalDateTime.now()));

        userStatsRepository.save(new UserStats(user));

        return user;
    }
}