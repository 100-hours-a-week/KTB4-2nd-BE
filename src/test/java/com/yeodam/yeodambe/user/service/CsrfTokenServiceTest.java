package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.security.TokenHasher;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenGenerator;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
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
class CsrfTokenServiceTest {

    @Autowired
    private CsrfTokenService csrfTokenService;

    @Autowired
    private CsrfTokenStore csrfTokenStore;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TokenHasher tokenHasher;

    @Test
    void 유효한_토큰이_있으면_다시_발급하지_않는다() {
        String first = csrfTokenService.issue("same-browser");
        String second = csrfTokenService.issue("same-browser");

        assertThat(second).isEqualTo(first);
        assertThat(csrfTokenStore.find("same-browser")).isEqualTo(first);
    }

    @Test
    void 토큰을_삭제한_뒤에는_새_토큰을_발급한다() {
        String first = csrfTokenService.issue("login-browser");

        csrfTokenStore.delete("login-browser");
        String second = csrfTokenService.issue("login-browser");

        assertThat(second).isNotEqualTo(first);
        assertThat(csrfTokenStore.find("login-browser")).isEqualTo(second);
    }

    @Test
    void 만료된_토큰은_새로_발급한다() throws Exception {
        csrfTokenStore.save("expired-browser", "expired-token");
        redis.expire("yeodam:test:auth:csrf:" + tokenHasher.hash("expired-browser"), java.time.Duration.ofMillis(1));
        Thread.sleep(20);

        String issued = csrfTokenService.issue("expired-browser");

        assertThat(issued).isNotEqualTo("expired-token");
        assertThat(csrfTokenStore.find("expired-browser")).isEqualTo(issued);
    }
}
