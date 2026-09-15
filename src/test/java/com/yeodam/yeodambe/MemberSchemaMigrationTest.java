// 회원 스키마의 초기 데이터와 제약조건을 검증합니다.
package com.yeodam.yeodambe;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.dao.DuplicateKeyException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MemberSchemaMigrationTest {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void systemAdminIsSeeded() {
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE email = ?",
                Long.class,
                "system@yeodam.invalid"
        );

        String nickname = jdbcTemplate.queryForObject(
                "SELECT nickname FROM users WHERE user_id = ?",
                String.class,
                1L
        );

        assertThat(userId).isEqualTo(1L);
        assertThat(nickname).isEqualTo("시스템");
    }

    @Test
    void activeEmailCannotBeDuplicated() {
        String email = "SameEmail@yeodam.test";
        jdbcTemplate.update(
                "INSERT INTO users (email, nickname) VALUES (?, ?)",
                email,
                "첫회원"
        );

        assertThatThrownBy(()->jdbcTemplate.update(
                "INSERT INTO users (email, nickname) VALUES (?, ?)",
                email,
                "둘째회원"
        )).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void deletedEmailCanBeReused() {
        String email = "rejoin@yeodam.test";

        jdbcTemplate.update(
                "INSERT INTO users (email, nickname) VALUES (?, ?)",
                email,
                "탈퇴회원"
        );

        jdbcTemplate.update(
                """
                UPDATE users
                SET deleted_at = CURRENT_TIMESTAMP(6)
                WHERE email = ? AND deleted_at IS NULL
                """,
                email
        );

        jdbcTemplate.update(
                "INSERT INTO users (email, nickname) VALUES (?, ?)",
                email,
                "재가입"
        );

        Long totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?",
                Long.class,
                email
        );

        Long activeCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM users
                WHERE email = ? AND deleted_at IS NULL
                """,
                Long.class,
                email
        );

        assertThat(totalCount).isEqualTo(2L);
        assertThat(activeCount).isEqualTo(1L);
    }

    @Test
    void emailIsCaseSensitive() {
        String uppercaseEmail = "Case@yeodam.test";
        String lowercaseEmail = "case@yeodam.test";

        jdbcTemplate.update(
                "INSERT INTO users (email, nickname) VALUES (?, ?)",
                uppercaseEmail,
                "대문자"
        );

        jdbcTemplate.update(
                "INSERT INTO users (email, nickname) VALUES (?, ?)",
                lowercaseEmail,
                "소문자"
        );

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email IN (?, ?)",
                Long.class,
                uppercaseEmail,
                lowercaseEmail
        );

        assertThat(count).isEqualTo(2L);
    }

    @Test
    void deletedOauthAccountCanBeReused() {
        String providerUserId = "kakao-user-100";

        jdbcTemplate.update(
                "INSERT INTO users (email, nickname) VALUES (?, ?)",
                "first@yeodam.test",
                "첫사용자"
        );

        jdbcTemplate.update(
                "INSERT INTO users (email, nickname) VALUES (?, ?)",
                "second@yeodam.test",
                "둘째사용자"
        );

        Long firstUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE email = ?",
                Long.class,
                "first@yeodam.test"
        );

        Long secondUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE email = ?",
                Long.class,
                "second@yeodam.test"
        );

        jdbcTemplate.update(
                """
                INSERT INTO oauth_accounts (user_id, provider, provider_user_id)
                VALUES (?, 'KAKAO', ?)
                """,
                firstUserId,
                providerUserId
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO oauth_accounts (user_id, provider, provider_user_id)
                VALUES (?, 'KAKAO', ?)
                """,
                secondUserId,
                providerUserId
        )).isInstanceOf(DuplicateKeyException.class);

        jdbcTemplate.update(
                """
                UPDATE oauth_accounts
                SET deleted_at = CURRENT_TIMESTAMP(6)
                WHERE provider = 'KAKAO'
                  AND provider_user_id = ?
                  AND deleted_at IS NULL
                """,
                providerUserId
        );

        jdbcTemplate.update(
                """
                INSERT INTO oauth_accounts (user_id, provider, provider_user_id)
                VALUES (?, 'KAKAO', ?)
                """,
                secondUserId,
                providerUserId
        );

        Long activeCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM oauth_accounts
                WHERE provider = 'KAKAO'
                  AND provider_user_id = ?
                  AND deleted_at IS NULL
                """,
                Long.class,
                providerUserId
        );

        assertThat(activeCount).isEqualTo(1L);
    }

    @Test
    void userCanHaveOnlyOneStatsRow() {
        String email = "stats@yeodam.test";

        jdbcTemplate.update(
                "INSERT INTO users (email, nickname) VALUES (?, ?)",
                email,
                "통계회원"
        );

        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE email = ?",
                Long.class,
                email
        );

        jdbcTemplate.update(
                "INSERT INTO user_stats (user_id) VALUES (?)",
                userId
        );

        Long storageMaximumBytes = jdbcTemplate.queryForObject(
                "SELECT storage_maximum_bytes FROM user_stats WHERE user_id = ?",
                Long.class,
                userId
        );

        assertThat(storageMaximumBytes).isEqualTo(30_000_000_000L);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO user_stats (user_id) VALUES (?)",
                userId
        )).isInstanceOf(DuplicateKeyException.class);
    }
}