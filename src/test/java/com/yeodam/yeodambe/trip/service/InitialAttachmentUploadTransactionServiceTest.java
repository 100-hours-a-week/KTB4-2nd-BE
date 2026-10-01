package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InitialAttachmentUploadTransactionServiceTest {
    @Autowired private InitialAttachmentUploadTransactionService service;
    @Autowired private InitialAttachmentUploadBatchRepository batches;
    @Autowired private TripRepository trips;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User owner;
    private Trip trip;
    private InitialAttachmentUploadBatch batch;

    @BeforeEach
    void setUp() {
        owner = users.save(new User(UUID.randomUUID() + "@yeodam.test", "완료회원"));
        trip = trips.save(new Trip(owner.getUserId(), "완료여행", LocalDate.now(), LocalDate.now()));
        batch = batches.save(new InitialAttachmentUploadBatch(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                trip.getId(), owner.getUserId(), 1, 12, false));
    }

    @Test
    void commitsProcessingStateBeforeReturningToCaller() {
        var result = start();

        assertThat(result.getId()).isEqualTo(batch.getId());
        assertThat(result.getStatus()).isEqualTo(InitialAttachmentUploadStatus.PROCESSING);
        assertThat(storedStatus()).isEqualTo("PROCESSING");
    }

    @Test
    void refusesProcessingAndFailedBatchesWithoutChangingState() {
        start();
        assertThatThrownBy(this::start)
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        assertThat(storedStatus()).isEqualTo("PROCESSING");

        setStatus("FAILED");
        assertThatThrownBy(this::start)
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        assertThat(storedStatus()).isEqualTo("FAILED");
    }

    @Test
    void returnsCompletedBatchWithoutRestartEvenWhenTripIsCompleted() {
        setStatus("COMPLETED");
        jdbcTemplate.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", trip.getId());

        var result = start();

        assertThat(result.getId()).isEqualTo(batch.getId());
        assertThat(result.getStatus()).isEqualTo(InitialAttachmentUploadStatus.COMPLETED);
        assertThat(storedStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void rejectsMissingUnknownOrMismatchedIdentifiers() {
        assertThatThrownBy(() -> service.startProcessing(trip.getId(), owner.getUserId(), null))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThatThrownBy(() -> service.startProcessing(trip.getId(), owner.getUserId(), " "))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThatThrownBy(() -> service.startProcessing(trip.getId(), owner.getUserId(), UUID.randomUUID().toString()))
                .isInstanceOf(InvalidAttachmentUploadException.class);

        var other = users.save(new User(UUID.randomUUID() + "@yeodam.test", "다른회원"));
        assertThatThrownBy(() -> service.startProcessing(trip.getId(), other.getUserId(), batch.getUploadId()))
                .isInstanceOf(TripNotFoundException.class);
        var otherTrip = trips.save(new Trip(owner.getUserId(), "다른여행", LocalDate.now(), LocalDate.now()));
        assertThatThrownBy(() -> service.startProcessing(otherTrip.getId(), owner.getUserId(), batch.getUploadId()))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThat(storedStatus()).isEqualTo("PENDING");
    }

    @Test
    void rejectsNonProcessingOrDeletedTripAndPreservesPendingBatch() {
        jdbcTemplate.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", trip.getId());
        assertThatThrownBy(this::start)
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        jdbcTemplate.update("UPDATE trips SET deleted_at = CURRENT_TIMESTAMP(6) WHERE trip_id = ?", trip.getId());
        assertThatThrownBy(this::start).isInstanceOf(TripNotFoundException.class);
        assertThat(storedStatus()).isEqualTo("PENDING");
    }

    @Test
    void onlyOneOfTwoConcurrentRequestsStartsProcessing() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> action = () -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                try {
                    start();
                    return true;
                } catch (TripInitialAttachmentUploadNotAllowedException expected) {
                    return false;
                }
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                go.countDown();
            }

            assertThat(new Boolean[]{first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)})
                    .containsExactlyInAnyOrder(true, false);
            assertThat(storedStatus()).isEqualTo("PROCESSING");
        }
    }

    private InitialAttachmentUploadBatch start() {
        return service.startProcessing(trip.getId(), owner.getUserId(), batch.getUploadId());
    }

    private String storedStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM initial_attachment_upload_batches WHERE upload_batch_id = ?",
                String.class, batch.getId());
    }

    private void setStatus(String status) {
        jdbcTemplate.update(
                "UPDATE initial_attachment_upload_batches SET status = ? WHERE upload_batch_id = ?",
                status, batch.getId());
    }
}
