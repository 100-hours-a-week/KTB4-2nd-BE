package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
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
    @Autowired private EntityManager entityManager;

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

    @Test
    void 탈퇴한_사용자가_소유자거나_공유대상인_활성_공유만_소프트_삭제한다() {
        User withdrawnUser = users.save(new User("withdrawn@share.test", "탈퇴회원"));
        User owner = users.save(new User("owner-withdrawal@share.test", "소유자"));
        User grantee = users.save(new User("grantee-withdrawal@share.test", "공유대상"));
        User unrelatedUser = users.save(new User("unrelated@share.test", "다른회원"));
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 30, 12, 0);
        LocalDateTime expiresAt = createdAt.plusDays(1);

        Trip ownedTrip = trips.save(new Trip(
                withdrawnUser.getUserId(), "탈퇴자의 여행", LocalDate.now(), LocalDate.now()));
        Trip sharedTrip = trips.save(new Trip(
                owner.getUserId(), "공유받은 여행", LocalDate.now(), LocalDate.now()));
        Trip unrelatedTrip = trips.save(new Trip(
                owner.getUserId(), "다른 여행", LocalDate.now(), LocalDate.now()));

        TripShareUser issuedShare = shares.save(new TripShareUser(
                ownedTrip.getId(), withdrawnUser.getUserId(), grantee.getUserId(),
                TripSharePermission.READ, expiresAt, createdAt));
        TripShareUser receivedShare = shares.save(new TripShareUser(
                sharedTrip.getId(), owner.getUserId(), withdrawnUser.getUserId(),
                TripSharePermission.WRITE, expiresAt, createdAt));
        TripShareUser unrelatedShare = shares.save(new TripShareUser(
                unrelatedTrip.getId(), owner.getUserId(), unrelatedUser.getUserId(),
                TripSharePermission.READ, expiresAt, createdAt));
        LocalDateTime previousDeletion = createdAt.minusDays(1);
        TripShareUser previouslyDeletedShare = shares.save(new TripShareUser(
                unrelatedTrip.getId(), owner.getUserId(), withdrawnUser.getUserId(),
                TripSharePermission.READ, expiresAt, createdAt));
        ReflectionTestUtils.setField(previouslyDeletedShare, "deletedAt", previousDeletion);
        entityManager.flush();
        entityManager.clear();

        LocalDateTime withdrawnAt = createdAt.plusHours(1);
        int updatedCount = shares.softDeleteByUserId(withdrawnUser.getUserId(), withdrawnAt);
        entityManager.flush();
        entityManager.clear();

        assertThat(updatedCount).isEqualTo(2);
        assertThat(shares.findByTripIdAndGranteeUserId(ownedTrip.getId(), grantee.getUserId()))
                .get().extracting(TripShareUser::getDeletedAt).isEqualTo(withdrawnAt);
        assertThat(shares.findByTripIdAndGranteeUserId(sharedTrip.getId(), withdrawnUser.getUserId()))
                .get().extracting(TripShareUser::getDeletedAt).isEqualTo(withdrawnAt);
        assertThat(shares.findByTripIdAndGranteeUserId(unrelatedTrip.getId(), unrelatedUser.getUserId()))
                .get().extracting(TripShareUser::getDeletedAt).isNull();
        assertThat(shares.findByTripIdAndGranteeUserId(unrelatedTrip.getId(), withdrawnUser.getUserId()))
                .get().extracting(TripShareUser::getDeletedAt).isEqualTo(previousDeletion);
    }
}
