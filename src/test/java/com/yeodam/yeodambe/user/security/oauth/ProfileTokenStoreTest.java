package com.yeodam.yeodambe.user.security.oauth;

import com.yeodam.yeodambe.user.security.TokenHasher;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
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
class ProfileTokenStoreTest {

    @Autowired
    private ProfileTokenStore profileTokenStore;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TokenHasher tokenHasher;

    @Test
    void 조회해도_명시적으로_삭제하기_전까지_토큰을_유지한다() {
        String token = "profile-token-lookup";
        KakaoUserIdentity identity = new KakaoUserIdentity(
                "123456789",
                "member@example.com",
                "https://k.kakaocdn.net/profile-token-thumbnail.jpg"
        );

        profileTokenStore.save(token, identity);

        assertThat(profileTokenStore.find(token)).contains(identity);
        assertThat(profileTokenStore.find(token)).contains(identity);

        profileTokenStore.delete(token);

        assertThat(profileTokenStore.find(token)).isEmpty();
    }

    @Test
    void 해시와_10분_유효기간을_저장한다() {
        String token = "profile-token-hash";

        profileTokenStore.save(
                token,
                new KakaoUserIdentity("123456789", "member@example.com")
        );

        assertThat(key(token)).doesNotContain(token);
        assertThat(redis.getExpire(key(token), TimeUnit.MILLISECONDS)).isBetween(595000L, 600000L);
    }

    @Test
    void 만료된_토큰은_조회할_수_없고_삭제된다() throws Exception {
        String token = "profile-token-expired";

        profileTokenStore.save(token, new KakaoUserIdentity("provider", "expire@example.com"));
        redis.expire(key(token), Duration.ofMillis(1));
        Thread.sleep(20);
        assertThat(profileTokenStore.find(token)).isEmpty();
        assertThat(redis.hasKey(key(token))).isFalse();
    }
    private String key(String token) {
        return "yeodam:test:auth:profile-token:" + tokenHasher.hash(token);
    }
}
