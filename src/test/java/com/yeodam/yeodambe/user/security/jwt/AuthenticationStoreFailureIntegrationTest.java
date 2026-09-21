package com.yeodam.yeodambe.user.security.jwt;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import com.yeodam.yeodambe.user.service.UserRegistrationService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({
        TestcontainersConfiguration.class,
        AuthenticationStoreFailureIntegrationTest.ProbeController.class
})
class AuthenticationStoreFailureIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRegistrationService userRegistrationService;

    @Autowired
    private AccessTokenIssuer accessTokenIssuer;

    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @RestController
    static class ProbeController {

        @GetMapping("/test/auth-store-failure-probe")
        String probe(@AuthenticationPrincipal Jwt jwt) {
            return jwt.getSubject();
        }
    }

    @Test
    void databaseFailureDuringJwtValidationReturnsServiceUnavailable()
            throws Exception {
        String unique = UUID.randomUUID().toString();
        User user = userRegistrationService.register(
                "database-failure-" + unique + "@yeodam.test",
                "저장소장애",
                OAuthProvider.KAKAO,
                "kakao-database-failure-" + unique
        );
        String accessToken = accessTokenIssuer.issue(
                user.getUserId(),
                "sid-database-down"
        );
        given(loginSessionStore.findBySid("sid-database-down"))
                .willThrow(new DataAccessResourceFailureException(
                        "Authentication database unavailable"
                ));

        mockMvc.perform(get("/test/auth-store-failure-probe")
                        .cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("""
                        {
                          "message": "AUTH_STORE_UNAVAILABLE",
                          "data": null
                        }
                        """));
    }
}
