package com.yeodam.yeodambe.user.security.csrf;

import com.yeodam.yeodambe.user.security.TokenHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.yeodam.yeodambe.TestcontainersConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Import;


import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CsrfTokenStoreTest {

    @Autowired
    private CsrfTokenStore csrfTokenStore;

    @Autowired
    private StringRedisTemplate redis;

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

        assertThat(redis.opsForValue().get(key("browser-rotation"))).isEqualTo("new-token");
    }

    @Test
    void storesBrowserHashAndSevenDayExpiration() {

        csrfTokenStore.save(
                "browser-ttl",
                "csrf-token"
        );

        assertThat(key("browser-ttl")).doesNotContain("browser-ttl");
        assertThat(redis.opsForValue().get(key("browser-ttl"))).isEqualTo("csrf-token");
        assertThat(redis.getExpire(key("browser-ttl"), TimeUnit.MILLISECONDS))
                .isBetween(604795000L, 604800000L);
    }

    @Test
    void expiredTokenCannotBeFoundAndIsDeleted() throws Exception {
        String browserContext = "browser-expired";

        csrfTokenStore.save(browserContext, "expired-token");
        redis.expire(key(browserContext), java.time.Duration.ofMillis(1));
        Thread.sleep(20);
        assertThat(csrfTokenStore.find(browserContext)).isNull();
        assertThat(redis.hasKey(key(browserContext))).isFalse();
    }
    private String key(String context) {
        return "yeodam:test:auth:csrf:" + tokenHasher.hash(context);
    }
}
