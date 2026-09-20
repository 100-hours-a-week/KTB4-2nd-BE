package com.yeodam.yeodambe.user.repository;

import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.service.UserRegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("local")
class LocalUserPersistenceTest {

    @Autowired
    private UserRegistrationService userRegistrationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void localH2SupportsFullUserRegistration() {
        User saved = userRegistrationService.register(
                "local-h2@yeodam.test",
                "로컬테스트",
                OAuthProvider.KAKAO,
                "local-provider-user"
        );

        assertThat(saved.getUserId()).isNotNull();

        LocalDateTime createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM users WHERE user_id = ?",
                LocalDateTime.class,
                saved.getUserId()
        );
        LocalDateTime updatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM users WHERE user_id = ?",
                LocalDateTime.class,
                saved.getUserId()
        );

        assertThat(createdAt).isNotNull();
        assertThat(updatedAt).isNotNull();
    }
}
