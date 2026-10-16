package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class AdditionalAttachmentExecutionRepositoryTest {
    @Autowired private AdditionalAttachmentExecutionRepository executions;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void findsLatestOwnedExecutionWithDeterministicTimestampTieOrder() {
        var fixture = createTrip();
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 14, 0);
        saveExecution(fixture, "ffffffff-ffff-ffff-ffff-ffffffffffff", now.minusMinutes(1));
        saveExecution(fixture, "00000000-0000-0000-0000-000000000001", now);
        String latestId = "00000000-0000-0000-0000-000000000002";
        saveExecution(fixture, latestId, now);
        var other = createTrip();
        saveExecution(other, UUID.randomUUID().toString(), now.plusMinutes(1));
        entityManager.clear();

        assertThat(executions.findFirstByTripIdAndUserIdOrderByCreatedAtDescExecutionIdDesc(
                fixture.tripId(), fixture.userId())).get()
                .extracting(AdditionalAttachmentExecution::getExecutionId).isEqualTo(latestId);
        assertThat(executions.findFirstByTripIdAndUserIdOrderByCreatedAtDescExecutionIdDesc(
                fixture.tripId(), other.userId())).isEmpty();
        assertThat(executions.findFirstByTripIdAndUserIdOrderByCreatedAtDescExecutionIdDesc(
                Long.MAX_VALUE, fixture.userId())).isEmpty();
    }

    @Test
    void findsOnlyExistingExecutionByIdForUpdate() {
        var fixture = createTrip();
        String executionId = UUID.randomUUID().toString();
        saveExecution(fixture, executionId, LocalDateTime.now());
        entityManager.clear();

        assertThat(executions.findForUpdate(executionId)).get()
                .extracting(AdditionalAttachmentExecution::getTripId).isEqualTo(fixture.tripId());
        assertThat(executions.findForUpdate(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void checksUnfinishedExecutionAndCanReadLatestTerminalExecution() {
        var fixture = createTrip();
        String executionId = UUID.randomUUID().toString();
        assertThat(executions.existsByTripIdAndEndedAtIsNull(fixture.tripId())).isFalse();
        saveExecution(fixture, executionId, LocalDateTime.now());
        assertThat(executions.existsByTripIdAndEndedAtIsNull(fixture.tripId())).isTrue();

        var execution = executions.findForUpdate(executionId).orElseThrow();
        execution.finish(AdditionalAttachmentExecution.Status.COMPLETED, null, LocalDateTime.now());
        executions.flush();
        entityManager.clear();

        assertThat(executions.existsByTripIdAndEndedAtIsNull(fixture.tripId())).isFalse();
        assertThat(executions.findFirstByTripIdAndUserIdOrderByCreatedAtDescExecutionIdDesc(
                fixture.tripId(), fixture.userId())).get()
                .extracting(AdditionalAttachmentExecution::getStatus)
                .isEqualTo(AdditionalAttachmentExecution.Status.COMPLETED);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void waitsForOtherTransactionAndReadsCommittedReservationBeforeUpdating() throws Exception {
        var fixture = createTrip();
        String executionId = UUID.randomUUID().toString();
        saveExecution(fixture, executionId, LocalDateTime.now());
        var locked = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(tx -> {
                        var execution = executions.findForUpdate(executionId).orElseThrow();
                        execution.reserve(1024L);
                        executions.flush();
                        locked.countDown();
                        await(release);
                    }));
            await(locked);
            var second = workers.submit(() -> new TransactionTemplate(transactionManager).execute(tx -> {
                secondStarted.countDown();
                var execution = executions.findForUpdate(executionId).orElseThrow();
                assertThat(execution.getReservedBytes()).isEqualTo(1024L);
                execution.reserve(2048L);
                return execution.getReservedBytes();
            }));
            await(secondStarted);
            assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(3072L);
            assertThat(executions.findById(executionId)).get()
                    .extracting(AdditionalAttachmentExecution::getReservedBytes).isEqualTo(3072L);
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            executions.deleteById(executionId);
            trips.deleteById(fixture.tripId());
            users.deleteById(fixture.userId());
        }
    }

    private Fixture createTrip() {
        Long userId = users.saveAndFlush(new User(UUID.randomUUID() + "@yeodam.test", "execution"))
                .getUserId();
        Long tripId = trips.saveAndFlush(new Trip(userId, "execution", LocalDate.now(), LocalDate.now()))
                .getId();
        return new Fixture(userId, tripId);
    }

    private void saveExecution(Fixture fixture, String executionId, LocalDateTime createdAt) {
        executions.saveAndFlush(new AdditionalAttachmentExecution(
                executionId, fixture.tripId(), fixture.userId(), 20, createdAt));
        jdbc.update("UPDATE additional_attachment_executions SET created_at = ? WHERE execution_id = ?",
                createdAt, executionId);
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private record Fixture(Long userId, Long tripId) {
    }
}
