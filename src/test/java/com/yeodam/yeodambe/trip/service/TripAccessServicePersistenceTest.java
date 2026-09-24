package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(TripAccessService.class)
class TripAccessServicePersistenceTest {
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripAccessService service;

    @Test
    void 소유한_미삭제_여행만_조회한다() {
        User owner = users.save(new User("owner@test.com", "소유자"));
        User other = users.save(new User("other@test.com", "다른회원"));
        Trip readable = trips.save(new Trip(
                owner.getUserId(), "조회여행", LocalDate.now(), LocalDate.now()));
        Trip deleted = new Trip(
                owner.getUserId(), "삭제여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(deleted, "deletedAt", LocalDateTime.now());
        trips.save(deleted);

        assertThat(service.requireReadableTrip(readable.getId(), owner.getUserId()).getId())
                .isEqualTo(readable.getId());
        assertThatThrownBy(() -> service.requireReadableTrip(readable.getId(), other.getUserId()))
                .isInstanceOf(TripNotFoundException.class);
        assertThatThrownBy(() -> service.requireReadableTrip(deleted.getId(), owner.getUserId()))
                .isInstanceOf(TripNotFoundException.class);
    }
}
