package com.yeodam.yeodambe.user.security.oauth;

import com.yeodam.yeodambe.user.security.TokenHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.yeodam.yeodambe.TestcontainersConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Import;


import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OAuthStateStoreTest {

    @Autowired
    private OAuthStateStore oauthStateStore;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TokenHasher tokenHasher;

    @Test
    void OAuth_상태는_한_번만_소비할_수_있다() {
        String state = "oauth-state-1";
        String browserContext = "browser-1";

        oauthStateStore.save(state, browserContext);

        assertThat(oauthStateStore.consume(state, browserContext))
                .isTrue();
        assertThat(oauthStateStore.consume(state, browserContext))
                .isFalse();
    }

    @Test
    void 다른_브라우저는_OAuth_상태를_소비할_수_없다() {
        String state = "oauth-state-2";

        oauthStateStore.save(state, "browser-1");

        assertThat(oauthStateStore.consume(state, "browser-2"))
                .isFalse();
        assertThat(oauthStateStore.consume(state, "browser-1"))
                .isTrue();
    }

    @Test
    void 해시와_5분_유효기간을_저장한다() {
        String state = "oauth-state-hash";
        String browserContext = "browser-hash";

        oauthStateStore.save(state, browserContext);

        assertThat(key(state)).doesNotContain(state);
        assertThat(redis.opsForValue().get(key(state))).isEqualTo(tokenHasher.hash(browserContext));
        assertThat(redis.getExpire(key(state), TimeUnit.MILLISECONDS)).isBetween(295000L, 300000L);
    }

    @Test
    void 만료된_OAuth_상태는_소비할_수_없고_삭제된다() throws Exception {
        String state = "oauth-state-expired";

        oauthStateStore.save(state, "browser-1");
        redis.expire(key(state), Duration.ofMillis(1));
        Thread.sleep(20);
        assertThat(oauthStateStore.consume(state, "browser-1")).isFalse();
        assertThat(redis.hasKey(key(state))).isFalse();
    }
    private String key(String token) {
        return "yeodam:test:auth:oauth-state:" + tokenHasher.hash(token);
    }
}
