package com.yeodam.yeodambe.user.security.csrf;

import com.yeodam.yeodambe.user.security.TokenHasher;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.yeodam.yeodambe.TestcontainersConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RedisCsrfTokenRepositoryTest {

    @Autowired
    private CsrfTokenStore csrfTokenStore;

    @Autowired
    private RedisCsrfTokenRepository csrfTokenRepository;

    @Test
    void loadsTokenForBrowserContextCookie() {
        csrfTokenStore.save("browser-1", "issued-token");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("CSRF_CONTEXT", "browser-1"));

        CsrfToken token = csrfTokenRepository.loadToken(request);

        assertThat(token).isNotNull();
        assertThat(token.getHeaderName()).isEqualTo("X-CSRF-TOKEN");
        assertThat(token.getToken()).isEqualTo("issued-token");
    }

    @Test
    void savesAndDeletesTokenForBrowserContextCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("CSRF_CONTEXT", "browser-save-delete"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        CsrfToken token = new DefaultCsrfToken(
                "X-CSRF-TOKEN", "_csrf", "saved-token"
        );

        csrfTokenRepository.saveToken(token, request, response);
        assertThat(csrfTokenStore.find("browser-save-delete"))
                .isEqualTo("saved-token");

        csrfTokenRepository.saveToken(null, request, response);
        assertThat(csrfTokenStore.find("browser-save-delete"))
                .isNull();
    }
}
