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
class LoginTicketStoreTest {

    @Autowired
    private LoginTicketStore loginTicketStore;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TokenHasher tokenHasher;

    @Test
    void 로그인_티켓은_한_번만_소비할_수_있다() {
        String ticket = "login-ticket-once";

        KakaoUserIdentity identity =
                new KakaoUserIdentity(
                        "123456789",
                        "member@example.com",
                        "https://k.kakaocdn.net/login-ticket-thumbnail.jpg"
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
    void 해시와_1분_유효기간을_저장한다() {
        String ticket = "login-ticket-hash";
        String browserContext = "browser-hash";

        loginTicketStore.save(
                ticket,
                new KakaoUserIdentity(
                        "123456789",
                        "member@example.com"
                ),
                browserContext
        );

        assertThat(key(ticket)).doesNotContain(ticket);
        assertThat(redis.getExpire(key(ticket), TimeUnit.MILLISECONDS)).isBetween(55000L, 60000L);
    }

    @Test
    void 다른_브라우저는_로그인_티켓을_소비할_수_없다() {
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

    @Test
    void 만료된_티켓은_소비할_수_없고_삭제된다() throws Exception {
        String ticket = "login-ticket-expired";

        loginTicketStore.save(ticket, new KakaoUserIdentity("provider", "expire@example.com"), "browser-1");
        redis.expire(key(ticket), Duration.ofMillis(1));
        Thread.sleep(20);
        assertThat(loginTicketStore.consume(ticket, "browser-1")).isEmpty();
        assertThat(redis.hasKey(key(ticket))).isFalse();
    }
    private String key(String token) {
        return "yeodam:test:auth:login-ticket:" + tokenHasher.hash(token);
    }
}
