package com.yeodam.yeodambe.trip.entity;

import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TripDraftJpaSchemaTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;

    @Test
    void JPA_스키마도_사용자와_제출_여행의_외래키를_강제한다() {
        assertThatThrownBy(() -> insertDraft(-1, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        Long userId = users.saveAndFlush(new User(System.nanoTime() + "@draft-schema.invalid", "초안")).getUserId();
        assertThatThrownBy(() -> insertDraft(userId, -1L))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertDraft(userId, null);
        assertThatThrownBy(() -> insertDraft(userId, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertDraft(long userId, Long submittedTripId) {
        jdbc.update("""
                INSERT INTO trip_drafts (user_id, region_codes, submitted_trip_id, created_at, updated_at)
                VALUES (?, '[]', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, userId, submittedTripId);
    }
}
