package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.SharedTestMysql;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.service.InvitedTripListService;
import com.yeodam.yeodambe.trip.service.response.InvitedTripListResponse;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InvitedTripListService.class, InvitedTripListRepositoryTest.Config.class})
class InvitedTripListRepositoryTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 12, 0);
    private static final LocalDate START_DATE = LocalDate.of(2026, 11, 20);
    @Autowired private TripShareUserRepository shares;
    @Autowired private TripRepository trips;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InvitedTripListService service;
    private Long ownerId;
    private Long granteeId;
    private Long otherId;

    @BeforeEach
    void fixture() {
        ownerId = user();
        granteeId = user();
        otherId = user();
    }

    @Test
    void 목록과_전체_개수는_유효한_수신_공유만_포함한다() {
        Trip readTrip = trip(ownerId, START_DATE);
        Trip writeTrip = trip(ownerId, START_DATE);
        share(readTrip, ownerId, granteeId, TripSharePermission.READ, NOW.plusSeconds(1));
        share(writeTrip, ownerId, granteeId, TripSharePermission.WRITE, NOW.plusHours(1));
        share(trip(ownerId, START_DATE), ownerId, granteeId, TripSharePermission.READ, NOW);
        share(trip(ownerId, START_DATE), ownerId, granteeId, TripSharePermission.READ, NOW.minusSeconds(1));
        TripShareUser revoked = share(trip(ownerId, START_DATE), ownerId, granteeId,
                TripSharePermission.READ, NOW.plusHours(1));
        ReflectionTestUtils.setField(revoked, "deletedAt", NOW);
        Trip deletedTrip = trip(ownerId, START_DATE);
        deletedTrip.softDelete(NOW);
        share(deletedTrip, ownerId, granteeId, TripSharePermission.READ, NOW.plusHours(1));
        share(trip(ownerId, START_DATE), otherId, granteeId, TripSharePermission.READ, NOW.plusHours(1));
        share(trip(ownerId, START_DATE), ownerId, otherId, TripSharePermission.READ, NOW.plusHours(1));
        share(trip(granteeId, START_DATE), granteeId, granteeId, TripSharePermission.READ, NOW.plusHours(1));
        flushAndClear();

        List<TripShareUser> result = shares.findInvitedPage(granteeId, NOW, null, null, PageRequest.of(0, 8));

        assertThat(result).extracting(TripShareUser::getTripId)
                .containsExactly(writeTrip.getId(), readTrip.getId());
        assertThat(result).extracting(TripShareUser::getPermission)
                .containsExactly(TripSharePermission.WRITE, TripSharePermission.READ);
        assertThat(shares.countInvitedTrips(granteeId, NOW)).isEqualTo(2L);
    }

    @Test
    void 시작일과_ID_내림차순으로_중복없이_이어지고_count는_커서와_무관하다() {
        Trip sameFirst = trip(ownerId, START_DATE);
        Trip sameLast = trip(ownerId, START_DATE);
        Trip earlier = trip(ownerId, START_DATE.minusDays(1));
        for (Trip trip : List.of(sameFirst, sameLast, earlier)) {
            share(trip, ownerId, granteeId, TripSharePermission.READ, NOW.plusHours(1));
        }
        flushAndClear();

        List<TripShareUser> first = shares.findInvitedPage(granteeId, NOW, null, null, PageRequest.of(0, 1));
        List<TripShareUser> second = shares.findInvitedPage(
                granteeId, NOW, START_DATE, sameLast.getId(), PageRequest.of(0, 2));

        assertThat(first).extracting(TripShareUser::getTripId).containsExactly(sameLast.getId());
        assertThat(second).extracting(TripShareUser::getTripId).containsExactly(sameFirst.getId(), earlier.getId());
        assertThat(shares.countInvitedTrips(granteeId, NOW)).isEqualTo(3L);
    }

    @Test
    void 다른_사용자의_정렬_경계를_전달해도_공유_범위는_확대되지_않는다() {
        Trip visible = trip(ownerId, START_DATE.minusDays(1));
        Trip otherOnly = trip(ownerId, START_DATE);
        share(visible, ownerId, granteeId, TripSharePermission.READ, NOW.plusHours(1));
        share(otherOnly, ownerId, otherId, TripSharePermission.WRITE, NOW.plusHours(1));
        flushAndClear();

        assertThat(shares.findInvitedPage(granteeId, NOW, START_DATE, otherOnly.getId(), PageRequest.of(0, 8)))
                .extracting(TripShareUser::getTripId).containsExactly(visible.getId());
        assertThat(shares.countInvitedTrips(granteeId, NOW)).isEqualTo(1L);
    }

    @Test
    void 재초대는_기존_공유를_한번만_노출하고_변경된_권한을_반환한다() {
        Trip trip = trip(ownerId, START_DATE);
        TripShareUser share = share(trip, ownerId, granteeId, TripSharePermission.READ, NOW);
        share.reactivate(TripSharePermission.WRITE, NOW.plusHours(24), NOW);
        flushAndClear();

        List<TripShareUser> result = shares.findInvitedPage(granteeId, NOW, null, null, PageRequest.of(0, 8));

        assertThat(result).extracting(TripShareUser::getId).containsExactly(share.getId());
        assertThat(result.getFirst().getPermission()).isEqualTo(TripSharePermission.WRITE);
        assertThat(shares.countInvitedTrips(granteeId, NOW)).isEqualTo(1L);
    }

    @Test
    void 실제_페이지_조회는_여행과_권한_N플러스1없이_4개_SQL로_응답한다() {
        for (int index = 0; index < 8; index++) {
            share(trip(ownerId, START_DATE), ownerId, granteeId, TripSharePermission.READ, NOW.plusHours(1));
        }
        flushAndClear();
        Statistics statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        InvitedTripListResponse response = service.findInvitedTrips(granteeId, null);

        assertThat(response.totalCount()).isEqualTo(8L);
        assertThat(response.items()).hasSize(7);
        assertThat(response.hasNext()).isTrue();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(4L);
    }

    @Test
    void Flyway가_수신자_유효성_복합_인덱스를_적용한다() {
        assertThat(jdbc.queryForObject("""
                SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'trip_share_users'
                  AND index_name = 'idx_trip_share_grantee_active'
                """, String.class)).isEqualTo("deleted_at,grantee_user_id,expires_at");
        assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
    }

    private Trip trip(Long userId, LocalDate startDate) {
        return trips.save(new Trip(userId, "공유여행", startDate, startDate.plusDays(7)));
    }

    private TripShareUser share(Trip trip, Long issuerId, Long recipientId,
                               TripSharePermission permission, LocalDateTime expiresAt) {
        return shares.save(new TripShareUser(trip.getId(), issuerId, recipientId, permission, expiresAt, NOW));
    }

    private Long user() {
        return users.save(new User(UUID.randomUUID() + "@invited.test", "회원")).getUserId();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        JdbcConnectionDetails mysql() throws SQLException {
            return SharedTestMysql.createDatabase();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-02T03:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        TripAttachmentStorageClient storage() {
            return mock(TripAttachmentStorageClient.class);
        }
    }
}
