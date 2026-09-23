package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.entity.CsrfTokenEntity;
import com.yeodam.yeodambe.user.repository.CsrfTokenRepository;
import com.yeodam.yeodambe.user.security.TokenHasher;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenGenerator;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({CsrfTokenService.class, CsrfTokenStore.class, CsrfTokenGenerator.class, TokenHasher.class})
class CsrfTokenServiceTest {

    @Autowired
    private CsrfTokenService csrfTokenService;

    @Autowired
    private CsrfTokenStore csrfTokenStore;

    @Autowired
    private CsrfTokenRepository csrfTokenRepository;

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
    void 만료된_토큰은_새로_발급한다() {
        csrfTokenRepository.save(new CsrfTokenEntity(
                tokenHasher.hash("expired-browser"),
                "expired-token",
                LocalDateTime.now().minusSeconds(1)
        ));

        String issued = csrfTokenService.issue("expired-browser");

        assertThat(issued).isNotEqualTo("expired-token");
        assertThat(csrfTokenStore.find("expired-browser")).isEqualTo(issued);
    }
}
