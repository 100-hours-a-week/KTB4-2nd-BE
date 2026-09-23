package com.yeodam.yeodambe.user.security.csrf;

import com.yeodam.yeodambe.user.entity.CsrfTokenEntity;
import com.yeodam.yeodambe.user.repository.CsrfTokenRepository;
import com.yeodam.yeodambe.user.security.TokenHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class CsrfTokenStore {

    private static final Duration TOKEN_TTL =
            Duration.ofDays(7);

    private final CsrfTokenRepository csrfTokenRepository;
    private final TokenHasher tokenHasher;

    @Transactional
    public String findOrCreate(String browserContext, Supplier<String> tokenGenerator) {
        String browserContextHash = tokenHasher.hash(browserContext);
        Optional<CsrfTokenEntity> result =
                csrfTokenRepository.findByBrowserContextHashForUpdate(browserContextHash);
        LocalDateTime now = LocalDateTime.now();

        if (result.isPresent() && !result.get().isExpired(now)) {
            return result.get().getTokenValue();
        }

        String token = tokenGenerator.get();
        LocalDateTime expiresAt = now.plus(TOKEN_TTL);
        if (result.isPresent()) {
            result.get().update(token, expiresAt);
        } else {
            csrfTokenRepository.saveAndFlush(new CsrfTokenEntity(
                    browserContextHash, token, expiresAt
            ));
        }
        return token;
    }

    @Transactional
    public void save(
            String browserContext,
            String token
    ) {
        String browserContextHash =
                tokenHasher.hash(browserContext);

        Optional<CsrfTokenEntity> result =
                csrfTokenRepository
                        .findByBrowserContextHashForUpdate(
                                browserContextHash
                        );

        LocalDateTime expiresAt =
                LocalDateTime.now().plus(TOKEN_TTL);

        if (result.isPresent()) {
            result.get().update(token, expiresAt);
            return;
        }

        csrfTokenRepository.save(new CsrfTokenEntity(
                browserContextHash,
                token,
                expiresAt
        ));
    }

    @Transactional
    public boolean matches(
            String browserContext,
            String token
    ) {
        if (browserContext == null
                || browserContext.isBlank()
                || token == null
                || token.isBlank()) {
            return false;
        }

        Optional<CsrfTokenEntity> result =
                findEntity(browserContext);

        if (result.isEmpty()) {
            return false;
        }

        CsrfTokenEntity entity = result.get();

        if (entity.isExpired(LocalDateTime.now())) {
            csrfTokenRepository.delete(entity);
            return false;
        }

        return token.equals(entity.getTokenValue());
    }

    @Transactional
    public String find(String browserContext) {
        if (browserContext == null
                || browserContext.isBlank()) {
            return null;
        }

        Optional<CsrfTokenEntity> result =
                findEntity(browserContext);

        if (result.isEmpty()) {
            return null;
        }

        CsrfTokenEntity entity = result.get();

        if (entity.isExpired(LocalDateTime.now())) {
            csrfTokenRepository.delete(entity);
            return null;
        }

        return entity.getTokenValue();
    }

    @Transactional
    public void delete(String browserContext) {
        if (browserContext == null
                || browserContext.isBlank()) {
            return;
        }

        csrfTokenRepository.deleteByBrowserContextHash(
                tokenHasher.hash(browserContext)
        );
    }

    private Optional<CsrfTokenEntity> findEntity(
            String browserContext
    ) {
        return csrfTokenRepository.findByBrowserContextHash(
                tokenHasher.hash(browserContext)
        );
    }
}
