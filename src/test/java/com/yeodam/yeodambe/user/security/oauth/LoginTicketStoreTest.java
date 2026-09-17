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
class LoginTicketStoreTest {

    @Autowired
    private LoginTicketStore loginTicketStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void ticketCanBeConsumedOnlyOnce() {
        String ticket = "login-ticket-once";

        KakaoUserIdentity identity =
                new KakaoUserIdentity(
                        "123456789",
                        "member@example.com"
                );

        loginTicketStore.save(
                ticket,
                identity,
                "browser-1"
        );

        assertThat(loginTicketStore.consume(ticket, "browser-1"))
                .contains(identity);

        assertThat(loginTicketStore.consume(ticket, "browser-1"))
                .isEmpty();
    }

    @Test
    void ticketExpiresAfterOneMinute() {
        String ticket = "login-ticket-ttl";

        loginTicketStore.save(
                ticket,
                new KakaoUserIdentity(
                        "123456789",
                        "member@example.com"
                ),
                "browser-1"
        );

        Long ttlSeconds = redisTemplate.getExpire(
                "auth:login-ticket:browser-1:" + ticket,
                TimeUnit.SECONDS
        );

        assertThat(ttlSeconds)
                .isBetween(50L, 60L);
    }

    @Test
    void differentBrowserCannotConsumeTicket() {
        String ticket = "login-ticket-browser";

        KakaoUserIdentity identity =
                new KakaoUserIdentity(
                        "123456789",
                        "member@example.com"
                );

        loginTicketStore.save(
                ticket,
                identity,
                "browser-1"
        );

        assertThat(
                loginTicketStore.consume(ticket, "browser-2")
        ).isEmpty();

        assertThat(
                loginTicketStore.consume(ticket, "browser-1")
        ).contains(identity);
    }
}