package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:${random.uuid};MODE=MySQL",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({TripAccessService.class, TripEditAuthorizationService.class, TripAccessServicePersistenceTest.TimeConfig.class})
class TripAccessServicePersistenceTest {
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripAccessService service;
    @Autowired private TripShareUserRepository shares;

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-30T03:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void 유효한_READ_WRITE는_읽을_수_있지만_기존_쓰기는_허용하지_않는다() {
        User owner = users.save(new User("owner@share.test", "소유자"));
        User reader = users.save(new User("reader@share.test", "조회자"));
        User writer = users.save(new User("writer@share.test", "편집자"));
        Trip trip = trips.save(new Trip(owner.getUserId(), "공유", LocalDate.now(), LocalDate.now()));
        LocalDateTime now = LocalDateTime.of(2026, 10, 30, 12, 0);
        shares.save(new TripShareUser(trip.getId(), owner.getUserId(), reader.getUserId(),
                TripSharePermission.READ, now.plusHours(1), now));
        shares.save(new TripShareUser(trip.getId(), owner.getUserId(), writer.getUserId(),
                TripSharePermission.WRITE, now.plusHours(1), now));

        assertThat(service.requireReadableTrip(trip.getId(), reader.getUserId()).getId()).isEqualTo(trip.getId());
        assertThat(service.requireReadableTrip(trip.getId(), writer.getUserId()).getId()).isEqualTo(trip.getId());
        assertThatThrownBy(() -> service.requireWritableTrip(trip.getId(), writer.getUserId()))
                .isInstanceOf(TripNotFoundException.class);
    }

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
