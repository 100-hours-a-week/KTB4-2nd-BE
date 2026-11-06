package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.SharedTestMysql;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TripReadAccessSnapshotRepositoryTest.Config.class)
class TripReadAccessSnapshotRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 12, 0);

    @Autowired
    private TripShareUserRepository shares;
    @Autowired
    private TripRepository trips;
    @Autowired
    private UserRepository users;
    @Autowired
    private EntityManager entityManager;

    @Test
    void 프로젝션은_현재_소유자의_유효하고_만료되지_않은_권한만_남긴다() {
        Long owner = user();
        Trip trip = trips.save(new Trip(owner, "여행", LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6)));
        Long reader = user();
        Long writer = user();
        save(trip, owner, reader, TripSharePermission.READ, NOW.plusHours(1));
        save(trip, owner, writer, TripSharePermission.WRITE, NOW.plusHours(1));
        save(trip, owner, user(), TripSharePermission.READ, NOW);
        save(trip, user(), user(), TripSharePermission.WRITE, NOW.plusHours(1));
        TripShareUser revoked = save(trip, owner, user(), TripSharePermission.READ, NOW.plusHours(1));
        ReflectionTestUtils.setField(revoked, "deletedAt", NOW);
        Trip other = trips.save(new Trip(owner, "다른 여행", LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6)));
        save(other, owner, user(), TripSharePermission.READ, NOW.plusHours(1));
        entityManager.flush();
        entityManager.clear();

        List<TripShareUserRepository.ReadGrantRow> result = shares.findReadableGrantsByTripIdAndOwnerId(
                trip.getId(), owner, NOW);

        assertThat(result).extracting(TripShareUserRepository.ReadGrantRow::getGranteeUserId)
                .containsExactlyInAnyOrder(reader, writer);
        assertThat(result).extracting(TripShareUserRepository.ReadGrantRow::getPermission)
                .containsExactlyInAnyOrder(TripSharePermission.READ, TripSharePermission.WRITE);
        assertThat(result).allSatisfy(row -> assertThat(row.getExpiresAt()).isEqualTo(NOW.plusHours(1)));
    }

    private Long user() {
        return users.save(new User(UUID.randomUUID() + "@snapshot.test", "회원")).getUserId();
    }

    private TripShareUser save(Trip trip, Long owner, Long member, TripSharePermission permission, LocalDateTime expiresAt) {
        return shares.save(new TripShareUser(trip.getId(), owner, member, permission, expiresAt, NOW));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        JdbcConnectionDetails mysql() throws SQLException {
            return SharedTestMysql.createDatabase();
        }
    }
}
