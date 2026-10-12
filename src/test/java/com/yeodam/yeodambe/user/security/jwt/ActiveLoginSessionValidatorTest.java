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
import org.springframework.dao.DataAccessResourceFailureException;
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
    void 세션과_활성_사용자가_일치하면_토큰을_허용한다() {
        User user = mock(User.class);
        given(loginSessionStore.findBySid("sid-1"))
                .willReturn(Optional.of(new LoginSession(42L, "refresh-hash")));
        given(userRepository.findById(42L)).willReturn(Optional.of(user));

        assertThat(validator.validate(jwt("42", "sid-1")).hasErrors()).isFalse();
    }

    @Test
    void 세션_조회_전에_SID가_없는_토큰을_거부한다() {
        assertThat(validator.validate(jwt("42", null)).hasErrors()).isTrue();

        verifyNoInteractions(loginSessionStore, userRepository);
    }

    @Test
    void DB에_세션이_없으면_토큰을_거부한다() {
        given(loginSessionStore.findBySid("sid-1"))
                .willReturn(Optional.empty());

        assertThat(validator.validate(jwt("42", "sid-1")).hasErrors()).isTrue();

        verifyNoInteractions(userRepository);
    }

    @Test
    void 세션이_다른_사용자의_것이면_토큰을_거부한다() {
        given(loginSessionStore.findBySid("sid-1"))
                .willReturn(Optional.of(new LoginSession(99L, "refresh-hash")));

        assertThat(validator.validate(jwt("42", "sid-1")).hasErrors()).isTrue();

        verifyNoInteractions(userRepository);
    }

    @Test
    void 사용자가_소프트_삭제되면_토큰을_거부한다() {
        User user = mock(User.class);
        given(user.getDeletedAt()).willReturn(LocalDateTime.now());
        given(loginSessionStore.findBySid("sid-1"))
                .willReturn(Optional.of(new LoginSession(42L, "refresh-hash")));
        given(userRepository.findById(42L)).willReturn(Optional.of(user));

        assertThat(validator.validate(jwt("42", "sid-1")).hasErrors()).isTrue();
    }

    @Test
    void DB를_사용할_수_없으면_인증_저장소_오류로_보고한다() {
        DataAccessResourceFailureException databaseFailure =
                new DataAccessResourceFailureException(
                        "Authentication database unavailable"
                );
        given(loginSessionStore.findBySid("sid-1"))
                .willThrow(databaseFailure);

        assertThatThrownBy(() -> validator.validate(jwt("42", "sid-1")))
                .isInstanceOfSatisfying(
                        OAuth2AuthenticationException.class,
                        exception -> {
                            assertThat(exception.getError().getErrorCode())
                                    .isEqualTo("auth_store_unavailable");
                            assertThat(exception).hasCause(databaseFailure);
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
