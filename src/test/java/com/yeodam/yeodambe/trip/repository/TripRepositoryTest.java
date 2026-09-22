package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TripRepositoryTest {
    @Autowired
    private TripRepository trips;
    @Autowired
    private UserRepository users;

    @Test
    void 완료_벌크_갱신_후_재조회하면_최신_상태를_반환한다() {
        User user = users.saveAndFlush(new User("user@example.com", "사용자"));
        Trip saved = trips.saveAndFlush(new Trip(
                user.getUserId(), "여행", LocalDate.now(), LocalDate.now()));
        Trip loaded = trips.findById(saved.getId()).orElseThrow();

        int updated = trips.finishInitialUpload(
                saved.getId(), user.getUserId(), ProcessingStatus.PROCESSING, ProcessingStatus.COMPLETED);
        Trip reloaded = trips.findById(saved.getId()).orElseThrow();

        assertThat(updated).isOne();
        assertThat(loaded.getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(reloaded.getProcessingStatus()).isEqualTo(ProcessingStatus.COMPLETED);
    }
}
