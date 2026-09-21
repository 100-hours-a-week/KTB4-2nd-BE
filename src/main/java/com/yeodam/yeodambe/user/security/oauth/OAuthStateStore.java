package com.yeodam.yeodambe.user.security.oauth;

import com.yeodam.yeodambe.user.entity.OAuthStateEntity;
import com.yeodam.yeodambe.user.repository.OAuthStateRepository;
import com.yeodam.yeodambe.user.security.TokenHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class OAuthStateStore {

    private static final Duration STATE_TTL =
            Duration.ofMinutes(5);

    private final OAuthStateRepository oauthStateRepository;
    private final TokenHasher tokenHasher;

    public void save(
            String state,
            String browserContext
    ) {
        LocalDateTime expiresAt =
                LocalDateTime.now().plus(STATE_TTL);

        OAuthStateEntity entity = new OAuthStateEntity(
                tokenHasher.hash(state),
                tokenHasher.hash(browserContext),
                expiresAt
        );

        oauthStateRepository.save(entity);
    }

    @Transactional
    public boolean consume(
            String state,
            String browserContext
    ) {
        if (state == null
                || state.isBlank()
                || browserContext == null
                || browserContext.isBlank()) {
            return false;
        }

        String stateHash = tokenHasher.hash(state);
        String browserContextHash =
                tokenHasher.hash(browserContext);

        Optional<OAuthStateEntity> result =
                oauthStateRepository.findByStateHashForUpdate(
                        stateHash
                );

        if (result.isEmpty()) {
            return false;
        }

        OAuthStateEntity entity = result.get();

        if (entity.isExpired(LocalDateTime.now())) {
            oauthStateRepository.delete(entity);
            return false;
        }

        if (!entity.matchesBrowserContext(browserContextHash)) {
            return false;
        }

        oauthStateRepository.delete(entity);
        return true;
    }
}