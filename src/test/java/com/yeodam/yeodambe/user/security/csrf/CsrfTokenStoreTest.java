package com.yeodam.yeodambe.user.security.csrf;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CsrfTokenStoreTest {

    @Autowired
    private CsrfTokenStore csrfTokenStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

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
    }

    @Test
    void tokenExpiresAfterSevenDays() {
        csrfTokenStore.save(
                "browser-ttl",
                "csrf-token"
        );

        Long ttlSeconds = redisTemplate.getExpire(
                "auth:csrf:browser-ttl",
                TimeUnit.SECONDS
        );

        assertThat(ttlSeconds)
                .isBetween(604790L, 604800L);
    }
}