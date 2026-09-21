package com.yeodam.yeodambe.user.security.csrf;

import com.yeodam.yeodambe.user.entity.CsrfTokenEntity;
import com.yeodam.yeodambe.user.repository.CsrfTokenRepository;
import com.yeodam.yeodambe.user.security.TokenHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({CsrfTokenStore.class, TokenHasher.class})
class CsrfTokenStoreTest {

    @Autowired
    private CsrfTokenStore csrfTokenStore;

    @Autowired
    private CsrfTokenRepository csrfTokenRepository;

    @Autowired
    private TokenHasher tokenHasher;

    @Test
    void tokenIsBoundToBrowserContext() {
        csrfTokenStore.save(
                "browser-1",
                "csrf-token"
        );

        assertThat(csrfTokenStore.matches(
                "browser-1",
                "csrf-token"
        )).isTrue();

        assertThat(csrfTokenStore.matches(
                "browser-2",
                "csrf-token"
        )).isFalse();
    }

    @Test
    void newTokenReplacesPreviousToken() {
        csrfTokenStore.save(
                "browser-rotation",
                "old-token"
        );

        csrfTokenStore.save(
                "browser-rotation",
                "new-token"
        );

        assertThat(csrfTokenStore.matches(
                "browser-rotation",
                "old-token"
        )).isFalse();

        assertThat(csrfTokenStore.matches(
                "browser-rotation",
                "new-token"
        )).isTrue();

        assertThat(csrfTokenRepository.count()).isEqualTo(1L);
    }

    @Test
    void storesBrowserHashAndSevenDayExpiration() {
        LocalDateTime beforeSave = LocalDateTime.now();

        csrfTokenStore.save(
                "browser-ttl",
                "csrf-token"
        );

        CsrfTokenEntity saved = csrfTokenRepository
                .findByBrowserContextHash(
                        tokenHasher.hash("browser-ttl")
                )
                .orElseThrow();

        assertThat(saved.getBrowserContextHash())
                .isNotEqualTo("browser-ttl");
        assertThat(saved.getTokenValue()).isEqualTo("csrf-token");
        assertThat(saved.getExpiresAt())
                .isBetween(
                        beforeSave.plusDays(7),
                        LocalDateTime.now().plusDays(7)
                );
    }

    @Test
    void expiredTokenCannotBeFoundAndIsDeleted() {
        String browserContext = "browser-expired";
        String browserContextHash = tokenHasher.hash(browserContext);

        csrfTokenRepository.save(new CsrfTokenEntity(
                browserContextHash,
                "expired-token",
                LocalDateTime.now().minusSeconds(1)
        ));

        assertThat(csrfTokenStore.find(browserContext)).isNull();
        assertThat(csrfTokenRepository.findByBrowserContextHash(
                browserContextHash
        )).isEmpty();
    }
}
