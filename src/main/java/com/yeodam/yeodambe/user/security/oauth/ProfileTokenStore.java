package com.yeodam.yeodambe.user.security.oauth;

import com.yeodam.yeodambe.user.entity.ProfileTokenEntity;
import com.yeodam.yeodambe.user.repository.ProfileTokenRepository;
import com.yeodam.yeodambe.user.security.TokenHasher;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ProfileTokenStore {

    private static final Duration TOKEN_TTL =
            Duration.ofMinutes(10);

    private final ProfileTokenRepository profileTokenRepository;
    private final TokenHasher tokenHasher;

    public void save(
            String token,
            KakaoUserIdentity identity
    ) {
        ProfileTokenEntity entity = new ProfileTokenEntity(
                tokenHasher.hash(token),
                identity.providerUserId(),
                identity.email(),
                LocalDateTime.now().plus(TOKEN_TTL)
        );

        profileTokenRepository.save(entity);
    }

    @Transactional
    public Optional<KakaoUserIdentity> find(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        Optional<ProfileTokenEntity> result =
                profileTokenRepository.findByTokenHashForUpdate(
                        tokenHasher.hash(token)
                );

        if (result.isEmpty()) {
            return Optional.empty();
        }

        ProfileTokenEntity entity = result.get();

        if (entity.isExpired(LocalDateTime.now())) {
            profileTokenRepository.delete(entity);
            return Optional.empty();
        }

        return Optional.of(new KakaoUserIdentity(
                entity.getProviderUserId(),
                entity.getEmail()
        ));
    }

    @Transactional
    public void delete(String token) {
        if (token == null || token.isBlank()) {
            return;
        }

        profileTokenRepository
                .findByTokenHashForUpdate(tokenHasher.hash(token))
                .ifPresent(profileTokenRepository::delete);
    }
}