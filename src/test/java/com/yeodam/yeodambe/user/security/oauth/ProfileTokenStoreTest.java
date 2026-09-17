package com.yeodam.yeodambe.user.security.oauth;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
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
class ProfileTokenStoreTest {

    @Autowired
    private ProfileTokenStore profileTokenStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void lookupKeepsTokenUntilExplicitDeletion() {
        String token = "profile-token-lookup";
        KakaoUserIdentity identity = new KakaoUserIdentity(
                "123456789",
                "member@example.com"
        );

        profileTokenStore.save(token, identity);

        assertThat(profileTokenStore.find(token)).contains(identity);
        assertThat(profileTokenStore.find(token)).contains(identity);

        profileTokenStore.delete(token);

        assertThat(profileTokenStore.find(token)).isEmpty();
    }

    @Test
    void tokenExpiresAfterTenMinutes() {
        String token = "profile-token-ttl";
        profileTokenStore.save(
                token,
                new KakaoUserIdentity("123456789", "member@example.com")
        );

        Long ttlSeconds = redisTemplate.getExpire(
                "auth:profile-token:" + token,
                TimeUnit.SECONDS
        );

        assertThat(ttlSeconds).isBetween(590L, 600L);
    }
}
