package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:${random.uuid};MODE=MySQL",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TripRepositoryTest {
    @Autowired
    private TripRepository trips;
    @Autowired
    private UserRepository users;

    @Test
    void 실패한_여행의_이름은_생성과_수정의_중복에서_제외한다() {
        User user = users.saveAndFlush(new User("failed-name@test.com", "실패회원"));
        Trip failed = saveTrip(user.getUserId(), ProcessingStatus.FAILED, null);

        assertThat(trips.existsByUserIdAndTripNameAndDeletedAtIsNullAndProcessingStatusNot(
                user.getUserId(), "여행", ProcessingStatus.FAILED
        ))
                .isFalse();
        assertThat(trips.existsByUserIdAndTripNameAndDeletedAtIsNullAndProcessingStatusNotAndIdNot(
                user.getUserId(), "여행", ProcessingStatus.FAILED, failed.getId() + 1
        )).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ProcessingStatus.class)
    void 삭제된_여행은_상태와_무관하게_이름을_점유하지_않는다(ProcessingStatus status) {
        User user = users.saveAndFlush(new User("deleted-name@test.com", "삭제회원"));
        Trip deleted = saveTrip(user.getUserId(), status, LocalDateTime.now());

        assertThat(trips.existsByUserIdAndTripNameAndDeletedAtIsNullAndProcessingStatusNot(
                user.getUserId(), "여행", ProcessingStatus.FAILED
        ))
                .isFalse();
        assertThat(trips.existsByUserIdAndTripNameAndDeletedAtIsNullAndProcessingStatusNotAndIdNot(
                user.getUserId(), "여행", ProcessingStatus.FAILED, deleted.getId() + 1
        )).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ProcessingStatus.class, names = {"PROCESSING", "COMPLETED", "CANCELED"})
    void 실패가_아닌_미삭제_여행은_이름을_점유한다(ProcessingStatus status) {
        User user = users.saveAndFlush(new User("occupied-name@test.com", "기존회원"));
        Trip occupied = saveTrip(user.getUserId(), status, null);

        assertThat(trips.existsByUserIdAndTripNameAndDeletedAtIsNullAndProcessingStatusNot(
                user.getUserId(), "여행", ProcessingStatus.FAILED
        ))
                .isTrue();
        assertThat(trips.existsByUserIdAndTripNameAndDeletedAtIsNullAndProcessingStatusNotAndIdNot(
                user.getUserId(), "여행", ProcessingStatus.FAILED, occupied.getId() + 1
        )).isTrue();
        assertThat(trips.existsByUserIdAndTripNameAndDeletedAtIsNullAndProcessingStatusNotAndIdNot(
                user.getUserId(), "여행", ProcessingStatus.FAILED, occupied.getId()
        )).isFalse();
    }

    @Test
    void 실패와_정상_여행의_이름이_같으면_정상_여행이_중복을_막는다() {
        User user = users.saveAndFlush(new User("mixed-name@test.com", "혼합회원"));
        Trip failed = saveTrip(user.getUserId(), ProcessingStatus.FAILED, null);
        saveTrip(user.getUserId(), ProcessingStatus.COMPLETED, null);

        assertThat(trips.existsByUserIdAndTripNameAndDeletedAtIsNullAndProcessingStatusNot(
                user.getUserId(), "여행", ProcessingStatus.FAILED
        ))
                .isTrue();
        assertThat(trips.existsByUserIdAndTripNameAndDeletedAtIsNullAndProcessingStatusNotAndIdNot(
                user.getUserId(), "여행", ProcessingStatus.FAILED, failed.getId()
        )).isTrue();
    }

    @Test
    void 다른_회원의_같은_여행명은_중복으로_판단하지_않는다() {
        User owner = users.saveAndFlush(new User("name-owner@test.com", "소유자"));
        User other = users.saveAndFlush(new User("name-other@test.com", "다른회원"));
        Trip occupied = saveTrip(owner.getUserId(), ProcessingStatus.COMPLETED, null);

        assertThat(trips.existsByUserIdAndTripNameAndDeletedAtIsNullAndProcessingStatusNot(
                other.getUserId(), "여행", ProcessingStatus.FAILED
        ))
                .isFalse();
        assertThat(trips.existsByUserIdAndTripNameAndDeletedAtIsNullAndProcessingStatusNotAndIdNot(
                other.getUserId(), "여행", ProcessingStatus.FAILED, occupied.getId() + 1
        )).isFalse();
    }

    private Trip saveTrip(Long userId, ProcessingStatus status, LocalDateTime deletedAt) {
        Trip trip = new Trip(userId, "여행", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3));
        ReflectionTestUtils.setField(trip, "processingStatus", status);
        if (deletedAt != null) {
            trip.softDelete(deletedAt);
        }
        return trips.saveAndFlush(trip);
    }

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
