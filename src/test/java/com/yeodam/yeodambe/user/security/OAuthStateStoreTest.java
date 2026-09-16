package com.yeodam.yeodambe;

import com.yeodam.yeodambe.user.security.OAuthStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OAuthStateStoreTest {

    @Autowired
    private OAuthStateStore oauthStateStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void stateCanBeConsumedOnlyOnce() {
        String state = "oauth-state-1";
        String browserContext = "browser-1";

        oauthStateStore.save(state, browserContext);

        assertThat(oauthStateStore.consume(state, browserContext))
                .isTrue();

        assertThat(oauthStateStore.consume(state, browserContext))
                .isFalse();
    }

    @Test
    void differentBrowserCannotConsumeState() {
        String state = "oauth-state-2";

        oauthStateStore.save(state, "browser-1");

        assertThat(oauthStateStore.consume(state, "browser-2"))
                .isFalse();

        assertThat(oauthStateStore.consume(state, "browser-1"))
                .isTrue();
    }

    @Test
    void stateExpiresAfterFiveMinutes() {
        String state = "oauth-state-ttl";

        oauthStateStore.save(state, "browser-1");

        Long ttlSeconds = redisTemplate.getExpire(
                "oauth:state:" + state,
                TimeUnit.SECONDS
        );

        assertThat(ttlSeconds).isBetween(240L, 300L);
    }
}