package com.yeodam.yeodambe.user.security.jwt;

import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.security.session.LoginSession;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ActiveLoginSessionValidatorTest {

    @Mock
    private LoginSessionStore loginSessionStore;
    @Mock
    private UserRepository userRepository;

    private ActiveLoginSessionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ActiveLoginSessionValidator(loginSessionStore, userRepository);
    }

    @Test
    void acceptsTokenWhenSessionAndActiveUserMatch() {
        User user = mock(User.class);
        given(loginSessionStore.findBySid("sid-1"))
                .willReturn(Optional.of(new LoginSession(42L, "refresh-hash")));
        given(userRepository.findById(42L)).willReturn(Optional.of(user));

        assertThat(validator.validate(jwt("42", "sid-1")).hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenWithoutSidBeforeLookingUpSession() {
        assertThat(validator.validate(jwt("42", null)).hasErrors()).isTrue();

        verifyNoInteractions(loginSessionStore, userRepository);
    }

    @Test
    void rejectsTokenWhenRedisSessionIsMissing() {
        given(loginSessionStore.findBySid("sid-1"))
                .willReturn(Optional.empty());

        assertThat(validator.validate(jwt("42", "sid-1")).hasErrors()).isTrue();

        verifyNoInteractions(userRepository);
    }

    @Test
    void rejectsTokenWhenSessionBelongsToAnotherUser() {
        given(loginSessionStore.findBySid("sid-1"))
                .willReturn(Optional.of(new LoginSession(99L, "refresh-hash")));

        assertThat(validator.validate(jwt("42", "sid-1")).hasErrors()).isTrue();

        verifyNoInteractions(userRepository);
    }

    @Test
    void rejectsTokenWhenUserIsSoftDeleted() {
        User user = mock(User.class);
        given(user.getDeletedAt()).willReturn(LocalDateTime.now());
        given(loginSessionStore.findBySid("sid-1"))
                .willReturn(Optional.of(new LoginSession(42L, "refresh-hash")));
        given(userRepository.findById(42L)).willReturn(Optional.of(user));

        assertThat(validator.validate(jwt("42", "sid-1")).hasErrors()).isTrue();
    }

    @Test
    void reportsAuthenticationStoreFailureWhenRedisIsUnavailable() {
        RedisConnectionFailureException redisFailure =
                new RedisConnectionFailureException("Redis unavailable");
        given(loginSessionStore.findBySid("sid-1"))
                .willThrow(redisFailure);

        assertThatThrownBy(() -> validator.validate(jwt("42", "sid-1")))
                .isInstanceOfSatisfying(
                        OAuth2AuthenticationException.class,
                        exception -> {
                            assertThat(exception.getError().getErrorCode())
                                    .isEqualTo("auth_store_unavailable");
                            assertThat(exception).hasCause(redisFailure);
                        }
                );

        verifyNoInteractions(userRepository);
    }

    private Jwt jwt(String subject, String sid) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject(subject);
        if (sid != null) {
            builder.claim("sid", sid);
        }
        return builder.build();
    }
}
