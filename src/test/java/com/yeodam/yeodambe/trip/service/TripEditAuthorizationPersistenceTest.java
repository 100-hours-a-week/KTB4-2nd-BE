package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.SharedTestMysql;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.WritePermissionRequiredException;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TripEditAuthorizationService.class, TripAccessService.class, TripEditAuthorizationPersistenceTest.Config.class})
class TripEditAuthorizationPersistenceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 12, 0);
    @Autowired private TripEditAuthorizationService service;
    @Autowired private TripAccessService existingAccess;
    @Autowired private TripShareUserRepository shares;
    @Autowired private TripRepository trips;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;
    private Long ownerId;
    private Long actorId;
    private Long otherUserId;
    private Trip trip;

    @BeforeEach
    void fixture() {
        ownerId = user("소유자");
        actorId = user("편집자");
        otherUserId = user("다른회원");
        trip = trips.saveAndFlush(new Trip(ownerId, "공유여행", LocalDate.now(), LocalDate.now()));
    }

    @Test
    void 편집_권한을_검사해도_기존_REST_소유자_전용_검사는_확대하지_않는다() {
        saveShare(ownerId, NOW.plusHours(1));

        assertThatCode(() -> service.requireWritePermission(trip, actorId)).doesNotThrowAnyException();
        assertThatThrownBy(() -> existingAccess.requireWritableTrip(trip.getId(), actorId))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void WRITE에서_READ로_변경하거나_철회하면_다음_검사에서_거절한다() {
        TripShareUser share = saveShare(ownerId, NOW.plusHours(1));
        assertThatCode(() -> service.requireWritePermission(trip, actorId)).doesNotThrowAnyException();

        share.reactivate(TripSharePermission.READ, NOW.plusHours(1), NOW);
        shares.saveAndFlush(share);
        entityManager.clear();
        assertThatThrownBy(() -> service.requireWritePermission(trip, actorId))
                .isInstanceOf(WritePermissionRequiredException.class);

        share.reactivate(TripSharePermission.WRITE, NOW.plusHours(1), NOW);
        ReflectionTestUtils.setField(share, "deletedAt", NOW);
        shares.saveAndFlush(share);
        entityManager.clear();
        assertThatThrownBy(() -> service.requireWritePermission(trip, actorId))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void 다른_여행과_다른_수신자의_공유를_재사용하지_않는다() {
        saveShare(ownerId, NOW.plusHours(1));
        Trip otherTrip = trips.saveAndFlush(new Trip(ownerId, "다른여행", LocalDate.now(), LocalDate.now()));

        assertThatThrownBy(() -> service.requireWritePermission(otherTrip, actorId))
                .isInstanceOf(TripNotFoundException.class);
        assertThatThrownBy(() -> service.requireWritePermission(trip, otherUserId))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void 실제_공유의_발급자가_현재_소유자와_다르면_거절한다() {
        saveShare(otherUserId, NOW.plusHours(1));

        assertThatThrownBy(() -> service.requireWritePermission(trip, actorId))
                .isInstanceOf(TripNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1})
    void DB에_저장한_한국_시각으로_만료_경계를_검사한다(int remainingSeconds) {
        saveShare(ownerId, NOW.plusSeconds(remainingSeconds));

        if (remainingSeconds > 0) {
            assertThatCode(() -> service.requireWritePermission(trip, actorId)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> service.requireWritePermission(trip, actorId))
                    .isInstanceOf(TripNotFoundException.class);
        }
    }

    private TripShareUser saveShare(Long issuerId, LocalDateTime expiresAt) {
        TripShareUser share = shares.saveAndFlush(new TripShareUser(
                trip.getId(), issuerId, actorId, TripSharePermission.WRITE, expiresAt, NOW.minusHours(1)));
        entityManager.clear();
        return share;
    }

    private Long user(String nickname) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@edit.test", nickname)).getUserId();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        JdbcConnectionDetails mysql() throws SQLException {
            return SharedTestMysql.createDatabase();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-30T03:00:00Z"), ZoneOffset.UTC);
        }
    }
}
