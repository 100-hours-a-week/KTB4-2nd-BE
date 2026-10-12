package com.yeodam.yeodambe.user.migration;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuthSchemaMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 모든_인증_테이블을_생성한다() {
        Long tableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN (
                      'oauth_states',
                      'login_tickets',
                      'profile_tokens',
                      'csrf_tokens',
                      'login_sessions'
                  )
                """,
                Long.class
        );

        assertThat(tableCount).isEqualTo(5L);
    }

    @Test
    void 로그인_흐름에_필요한_프로필_이미지_URL_컬럼을_추가한다() {
        Long columnCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name IN ('users', 'login_tickets', 'profile_tokens')
                  AND column_name = 'profile_image_url'
                """,
                Long.class
        );

        assertThat(columnCount).isEqualTo(3L);
    }

    @Test
    void OAuth_상태_해시는_중복될_수_없다() {
        String stateHash = "a".repeat(64);

        jdbcTemplate.update(
                """
                INSERT INTO oauth_states (
                    state_hash,
                    browser_context_hash,
                    expires_at
                ) VALUES (?, ?, DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 5 MINUTE))
                """,
                stateHash,
                "b".repeat(64)
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO oauth_states (
                    state_hash,
                    browser_context_hash,
                    expires_at
                ) VALUES (?, ?, DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 5 MINUTE))
                """,
                stateHash,
                "c".repeat(64)
        )).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void CSRF_토큰은_브라우저_컨텍스트별로_행을_하나만_허용한다() {
        String browserContextHash = "d".repeat(64);

        jdbcTemplate.update(
                """
                INSERT INTO csrf_tokens (
                    browser_context_hash,
                    token_value,
                    expires_at
                ) VALUES (?, ?, DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 7 DAY))
                """,
                browserContextHash,
                "first-csrf-token"
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO csrf_tokens (
                    browser_context_hash,
                    token_value,
                    expires_at
                ) VALUES (?, ?, DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 7 DAY))
                """,
                browserContextHash,
                "second-csrf-token"
        )).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 로그인_세션에는_기존_사용자와_고유한_리프레시_토큰_해시가_필요하다() {
        jdbcTemplate.update(
                "INSERT INTO users (email, nickname) VALUES (?, ?)",
                "auth-session@yeodam.test",
                "세션회원"
        );

        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM users WHERE email = ?",
                Long.class,
                "auth-session@yeodam.test"
        );

        String refreshTokenHash = "e".repeat(64);

        jdbcTemplate.update(
                """
                INSERT INTO login_sessions (
                    sid,
                    user_id,
                    refresh_token_hash,
                    expires_at
                ) VALUES (?, ?, ?, DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 7 DAY))
                """,
                "00000000-0000-0000-0000-000000000001",
                userId,
                refreshTokenHash
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO login_sessions (
                    sid,
                    user_id,
                    refresh_token_hash,
                    expires_at
                ) VALUES (?, ?, ?, DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 7 DAY))
                """,
                "00000000-0000-0000-0000-000000000002",
                userId,
                refreshTokenHash
        )).isInstanceOf(DuplicateKeyException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO login_sessions (
                    sid,
                    user_id,
                    refresh_token_hash,
                    expires_at
                ) VALUES (?, ?, ?, DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 7 DAY))
                """,
                "00000000-0000-0000-0000-000000000003",
                Long.MAX_VALUE,
                "f".repeat(64)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }
}
