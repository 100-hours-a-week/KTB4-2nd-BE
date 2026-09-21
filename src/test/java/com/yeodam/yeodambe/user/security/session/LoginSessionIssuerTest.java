package com.yeodam.yeodambe.user.security.session;

import com.yeodam.yeodambe.user.security.TokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class LoginSessionIssuerTest {

    @Mock
    private SessionIdGenerator sessionIdGenerator;

    @Mock
    private RefreshTokenGenerator refreshTokenGenerator;

    @Mock
    private TokenHasher tokenHasher;

    @Mock
    private LoginSessionStore loginSessionStore;

    private LoginSessionIssuer loginSessionIssuer;

    @BeforeEach
    void setUp() {
        loginSessionIssuer = new LoginSessionIssuer(
                sessionIdGenerator,
                refreshTokenGenerator,
                loginSessionStore,
                tokenHasher
        );
    }

    @Test
    void issuesSessionAndStoresOnlyRefreshTokenHash() {
        given(sessionIdGenerator.generate()).willReturn("sid-1");
        given(refreshTokenGenerator.generate()).willReturn("raw-refresh-token");
        given(tokenHasher.hash("raw-refresh-token"))
                .willReturn("refresh-token-hash");

        IssuedLoginSession issued = loginSessionIssuer.issue(42L);

        assertThat(issued).isEqualTo(
                new IssuedLoginSession("sid-1", "raw-refresh-token")
        );
        then(loginSessionStore).should()
                .save("sid-1", 42L, "refresh-token-hash");
    }
}
