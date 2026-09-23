package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDate;
import java.time.LocalDateTime;

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

    @Test
    void 소유한_완료_미삭제_여행을_조회한다() {
        User user = users.saveAndFlush(new User("favorite@example.com", "사용자"));
        Trip saved = trips.saveAndFlush(new Trip(
                user.getUserId(), "여행", LocalDate.now(), LocalDate.now()));
        trips.finishInitialUpload(
                saved.getId(), user.getUserId(), ProcessingStatus.PROCESSING, ProcessingStatus.COMPLETED);

        var found = trips.findByIdAndUserIdAndProcessingStatusAndDeletedAtIsNull(
                saved.getId(), user.getUserId(), ProcessingStatus.COMPLETED);

        assertThat(found).isPresent();
    }

    @Test
    void 다른_사용자의_여행은_조회하지_않는다() {
        User owner = users.saveAndFlush(new User("owner@example.com", "소유자"));
        User other = users.saveAndFlush(new User("other@example.com", "다른사용자"));
        Trip saved = trips.saveAndFlush(new Trip(
                owner.getUserId(), "여행", LocalDate.now(), LocalDate.now()));
        trips.finishInitialUpload(
                saved.getId(), owner.getUserId(), ProcessingStatus.PROCESSING, ProcessingStatus.COMPLETED);

        var found = trips.findByIdAndUserIdAndProcessingStatusAndDeletedAtIsNull(
                saved.getId(), other.getUserId(), ProcessingStatus.COMPLETED);

        assertThat(found).isEmpty();
    }

    @Test
    void 처리중인_여행은_조회하지_않는다() {
        User user = users.saveAndFlush(new User("processing@example.com", "처리중회원"));
        Trip saved = trips.saveAndFlush(new Trip(
                user.getUserId(), "여행", LocalDate.now(), LocalDate.now()));

        var found = trips.findByIdAndUserIdAndProcessingStatusAndDeletedAtIsNull(
                saved.getId(), user.getUserId(), ProcessingStatus.COMPLETED);

        assertThat(found).isEmpty();
    }

    @Test
    void 삭제된_여행은_조회하지_않는다() {
        User user = users.saveAndFlush(new User("deleted@example.com", "삭제회원"));
        Trip saved = trips.saveAndFlush(new Trip(
                user.getUserId(), "여행", LocalDate.now(), LocalDate.now()));
        trips.finishInitialUpload(
                saved.getId(), user.getUserId(), ProcessingStatus.PROCESSING, ProcessingStatus.COMPLETED);
        trips.softDeleteByUserId(user.getUserId(), LocalDateTime.now());

        var found = trips.findByIdAndUserIdAndProcessingStatusAndDeletedAtIsNull(
                saved.getId(), user.getUserId(), ProcessingStatus.COMPLETED);

        assertThat(found).isEmpty();
    }
}
