package com.yeodam.yeodambe.user.security;

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
class LoginSessionStoreTest {

    @Autowired
    private LoginSessionStore loginSessionStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void savesAndFindsSessionBySid() {
        loginSessionStore.save("sid-1", 42L, "refresh-hash-1");

        assertThat(loginSessionStore.findBySid("sid-1"))
                .contains(new LoginSession(42L, "refresh-hash-1"));
        assertThat(loginSessionStore.findBySid("missing-sid"))
                .isEmpty();
    }

    @Test
    void sessionExpiresAfterSevenDays() {
        loginSessionStore.save("sid-ttl", 42L, "refresh-hash-2");

        Long ttlSeconds = redisTemplate.getExpire(
                "auth:session:sid-ttl",
                TimeUnit.SECONDS
        );

        assertThat(ttlSeconds).isBetween(604790L, 604800L);
    }
}
