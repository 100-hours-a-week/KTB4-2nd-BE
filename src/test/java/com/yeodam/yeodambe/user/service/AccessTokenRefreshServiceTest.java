package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.exception.RefreshTokenInvalidOrExpiredException;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.security.TokenHasher;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.session.LoginSession;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import com.yeodam.yeodambe.user.security.session.RefreshTokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AccessTokenRefreshServiceTest {

    @Mock
    private TokenHasher tokenHasher;

    @Mock
    private RefreshTokenGenerator refreshTokenGenerator;

    @Mock
    private LoginSessionStore loginSessionStore;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccessTokenIssuer accessTokenIssuer;

    private AccessTokenRefreshService service;

    @BeforeEach
    void setUp() {
        service = new AccessTokenRefreshService(
                tokenHasher,
                refreshTokenGenerator,
                loginSessionStore,
                userRepository,
                accessTokenIssuer
        );
    }

    @Test
    void rejectsMissingRefreshTokenBeforeReadingSession() {
        assertThatThrownBy(() -> service.refresh(null))
                .isInstanceOf(RefreshTokenInvalidOrExpiredException.class);

        verifyNoInteractions(
                tokenHasher,
                refreshTokenGenerator,
                loginSessionStore,
                userRepository,
                accessTokenIssuer
        );
    }

    @Test
    void rotatesRefreshTokenAndReturnsNewTokens() {
        User user = mock(User.class);
        given(tokenHasher.hash("old-refresh-token"))
                .willReturn("old-refresh-hash");
        given(loginSessionStore.findSidByRefreshTokenHash("old-refresh-hash"))
                .willReturn(Optional.of("sid-1"));
        given(loginSessionStore.findBySid("sid-1"))
                .willReturn(Optional.of(new LoginSession(42L, "old-refresh-hash")));
        given(userRepository.findById(42L))
                .willReturn(Optional.of(user));
        given(user.getDeletedAt()).willReturn(null);
        given(user.getUserId()).willReturn(42L);
        given(refreshTokenGenerator.generate())
                .willReturn("new-refresh-token");
        given(tokenHasher.hash("new-refresh-token"))
                .willReturn("new-refresh-hash");
        given(accessTokenIssuer.issue(42L, "sid-1"))
                .willReturn("new-access-token");
        given(loginSessionStore.rotate(
                "old-refresh-hash",
                "new-refresh-hash"
        )).willReturn(Optional.of(42L));

        AccessTokenRefreshService.Result result =
                service.refresh("old-refresh-token");

        assertThat(result).isEqualTo(
                new AccessTokenRefreshService.Result(
                        "new-access-token",
                        "new-refresh-token"
                )
        );
        then(loginSessionStore).should().rotate(
                "old-refresh-hash",
                "new-refresh-hash"
        );
    }

    @Test
    void rejectsRequestThatLosesRefreshTokenRotationRace() {
        User user = mock(User.class);
        given(tokenHasher.hash("old-refresh-token"))
                .willReturn("old-refresh-hash");
        given(loginSessionStore.findSidByRefreshTokenHash("old-refresh-hash"))
                .willReturn(Optional.of("sid-1"));
        given(loginSessionStore.findBySid("sid-1"))
                .willReturn(Optional.of(new LoginSession(42L, "old-refresh-hash")));
        given(userRepository.findById(42L))
                .willReturn(Optional.of(user));
        given(user.getDeletedAt()).willReturn(null);
        given(user.getUserId()).willReturn(42L);
        given(refreshTokenGenerator.generate())
                .willReturn("new-refresh-token");
        given(tokenHasher.hash("new-refresh-token"))
                .willReturn("new-refresh-hash");
        given(accessTokenIssuer.issue(42L, "sid-1"))
                .willReturn("new-access-token");
        given(loginSessionStore.rotate(
                "old-refresh-hash",
                "new-refresh-hash"
        )).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("old-refresh-token"))
                .isInstanceOf(RefreshTokenInvalidOrExpiredException.class);

        then(loginSessionStore).should().rotate(
                "old-refresh-hash",
                "new-refresh-hash"
        );
    }
}
