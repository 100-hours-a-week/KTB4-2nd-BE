package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripDraftMigrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void 부분_초안을_저장하고_사용자당_한_행만_허용한다() {
        long userId = System.nanoTime();
        jdbc.update("INSERT INTO users (user_id, email, nickname) VALUES (?, ?, '초안')", userId, userId + "@test.invalid");
        jdbc.update("INSERT INTO trip_drafts (user_id, region_codes) VALUES (?, '[]')", userId);

        assertThat(jdbc.queryForObject("SELECT draft_id FROM trip_drafts WHERE user_id = ?", Long.class, userId)).isPositive();
        assertThat(jdbc.queryForObject("SELECT trip_name FROM trip_drafts WHERE user_id = ?", String.class, userId)).isNull();
        assertThat(jdbc.queryForObject("SELECT start_date FROM trip_drafts WHERE user_id = ?", java.sql.Date.class, userId)).isNull();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO trip_drafts (user_id, region_codes) VALUES (?, '[]')", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE trip_drafts SET submitted_trip_id = -1 WHERE user_id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO trip_drafts (user_id, region_codes) VALUES (-1, '[]')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
