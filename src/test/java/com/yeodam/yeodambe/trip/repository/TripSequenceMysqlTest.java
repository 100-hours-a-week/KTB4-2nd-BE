package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.SharedTestMysql;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import jakarta.persistence.EntityManager;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TripSequenceMysqlTest.MysqlConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TripSequenceMysqlTest {
    @Autowired TripRepository trips;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager em;
    @TestConfiguration(proxyBeanMethods = false)
    static class MysqlConfig {
        @Bean JdbcConnectionDetails mysql() throws SQLException { return SharedTestMysql.createDatabase(); }
    }
    @Test
    void newTripStartsWithZeroSequence() {
        var user = users.save(new User(UUID.randomUUID() + "@sequence.test", "순서검증"));
        var trip = trips.saveAndFlush(new Trip(user.getUserId(), "순서 검증", LocalDate.now(), LocalDate.now()));
        assertThat(jdbc.queryForObject("SELECT change_sequence FROM trips WHERE trip_id = ?", Long.class, trip.getId())).isZero();
    }

    @Test
    void incrementRollsBackTogetherWithTheMutation() {
        Long id = fixture();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var trip = trips.findActiveForUpdate(id).orElseThrow();
            trip.changeInformation("바뀐 이름", trip.getStartDate(), trip.getEndDate());
            trips.advanceSequence(id);
            status.setRollbackOnly();
        });
        assertThat(sequence(id)).isZero();
        assertThat(trips.findById(id).orElseThrow().getTripName()).isEqualTo("순서 검증");
    }

    @Test
    void anEntityLoadedBeforeIncrementCannotOverwriteTheDatabaseSequence() {
        Long id = fixture();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var trip = trips.findActiveForUpdate(id).orElseThrow();
            trips.advanceSequence(id);
            trip.changeInformation("변경된 여행", trip.getStartDate(), trip.getEndDate());
            em.flush();
        });
        assertThat(sequence(id)).isEqualTo(1);
        assertThat(trips.findById(id).orElseThrow().getSequence()).isEqualTo(1);
    }

    @Test
    void concurrentIncrementsFromTwoTransactionsDoNotLoseNumbers() throws Exception {
        Long id = fixture();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> incrementTenTimes(id, start));
            var second = pool.submit(() -> incrementTenTimes(id, start));
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        assertThat(sequence(id)).isEqualTo(20);
    }

    private void incrementTenTimes(Long id, CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        for (int i = 0; i < 10; i++) {
            new TransactionTemplate(manager).executeWithoutResult(status -> trips.advanceSequence(id));
        }
    }

    private Long fixture() {
        var user = users.save(new User(UUID.randomUUID() + "@sequence.test", "순서검증"));
        return trips.saveAndFlush(new Trip(user.getUserId(), "순서 검증", LocalDate.now(), LocalDate.now())).getId();
    }

    private long sequence(Long id) {
        return jdbc.queryForObject("SELECT change_sequence FROM trips WHERE trip_id = ?", Long.class, id);
    }
}
