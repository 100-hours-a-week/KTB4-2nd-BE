package com.yeodam.yeodambe;

import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.service.UserRegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UserRegistrationServiceTest {

    @Autowired
    private UserRegistrationService userRegistrationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void registrationCreatesAllMemberData() {
        User user = userRegistrationService.register(
                "signup@yeodam.test",
                "가입회원",
                OAuthProvider.KAKAO,
                "kakao-signup-1"
        );

        Long userId = user.getUserId();

        Long oauthCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth_accounts WHERE user_id = ?",
                Long.class,
                userId
        );

        Long consentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consents WHERE user_id = ? AND is_agreed = TRUE",
                Long.class,
                userId
        );

        Long statsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_stats WHERE user_id = ?",
                Long.class,
                userId
        );

        assertThat(userId).isNotNull();
        assertThat(oauthCount).isEqualTo(1L);
        assertThat(consentCount).isEqualTo(1L);
        assertThat(statsCount).isEqualTo(1L);
    }

    @Test
    void registrationRollsBackWhenOAuthAccountSaveFails() {
        String email = "rollback@yeodam.test";

        assertThatThrownBy(() -> userRegistrationService.register(
                email,
                "롤백회원",
                OAuthProvider.KAKAO,
                null
        )).isInstanceOf(DataIntegrityViolationException.class);

        Long userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?",
                Long.class,
                email
        );

        assertThat(userCount).isZero();
    }
}
