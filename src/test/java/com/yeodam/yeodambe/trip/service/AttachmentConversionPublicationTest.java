package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.client.InvalidAttachmentConversionMessageException;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.AttachmentConversionOutboxRepository;
import com.yeodam.yeodambe.trip.repository.AttachmentConversionTaskRepository;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionPublication;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AttachmentConversionPublicationTest {
    @Autowired private AttachmentConversionTransactionService transactions;
    @Autowired private AttachmentConversionOutboxRepository outboxes;
    @MockitoSpyBean private AttachmentConversionTaskRepository tasks;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    private AttachmentConversionOutbox outbox;
    private String executionId;

    @BeforeEach
    void prepare() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var user = new User(UUID.randomUUID() + "@yeodam.test", "receipt");
            entityManager.persist(user);
            var trip = new Trip(user.getUserId(), "receipt", LocalDate.now(), LocalDate.now());
            entityManager.persist(trip);
            executionId = UUID.randomUUID().toString();
            entityManager.persist(new TripCreationExecution(executionId, trip.getId(), user.getUserId(), 1));
            var batch = new InitialAttachmentUploadBatch(UUID.randomUUID().toString(), executionId,
                    trip.getId(), user.getUserId(), 1, 1, true);
            entityManager.persist(batch);
            var task = new AttachmentConversionTask(UUID.randomUUID().toString(), AttachmentConversionTask.Kind.INITIAL,
                    batch.getId(), trip.getId(), AttachmentConversionTask.Lane.LIGHT, 1, 100);
            entityManager.persist(task);
            outbox = new AttachmentConversionOutbox(UUID.randomUUID().toString(), task.getId(), 1, trip.getId(),
                    "yeodam-image-initial-light", "{\"schemaVersion\":1,\"taskId\":\"" + task.getId() + "\"}", "receipt-test");
            entityManager.persist(outbox);
        });
    }

    @Test
    void 수신_확인은_AI_상태를_진행하지_않고_파티션과_오프셋을_기록한다() {
        var before = aiPublishedAt();
        transactions.recordPublished(publication("topic-id", 0, 12));
        var stored = stored();
        assertThat(stored.getStatus()).isEqualTo(AttachmentConversionOutbox.Status.PUBLISHED);
        assertThat(stored.getRecordPartition()).isZero();
        assertThat(stored.getRecordOffset()).isEqualTo(12);
        assertThat(stored.getPublicationVersion()).isEqualTo(1);
        assertThat(aiPublishedAt()).isEqualTo(before);
        assertThat(tasks.findById(outbox.getTaskId()).orElseThrow().getStatus())
                .isEqualTo(AttachmentConversionTask.Status.WAITING);
    }

    @Test
    void 새_위치는_발행_버전을_증가시킨다() {
        transactions.recordPublished(publication("topic-id", 0, 12));
        var before = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
        transactions.recordPublished(publication("topic-id", 0, 13));
        var after = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
        assertThat(stored().getPublishedAt()).isBetween(before, after);
        transactions.recordPublished(publication("other-topic-id", 0, 1));
        assertThat(stored().getRecordOffset()).isEqualTo(1);
        assertThat(stored().getPublicationVersion()).isEqualTo(3);
    }

    @Test
    void 중복_수신_확인은_발행_버전을_증가시키지_않는다() {
        transactions.recordPublished(publication(null, 0, 12));
        var publishedAt = stored().getPublishedAt();
        transactions.recordPublished(publication(null, 0, 12));
        assertThat(stored().getPublicationVersion()).isEqualTo(1);
        assertThat(stored().getPublishedAt()).isEqualTo(publishedAt);
    }

    @Test
    void 오래된_오프셋은_최신_위치를_덮어쓰지_않는다() {
        transactions.recordPublished(publication("topic-id", 0, 12));
        transactions.recordPublished(publication("topic-id", 0, 11));
        assertThat(stored().getRecordOffset()).isEqualTo(12);
        assertThat(stored().getPublicationVersion()).isEqualTo(1);
    }

    @Test
    void 잘못된_수신_확인으로_아웃박스를_확정할_수_없다() {
        var correct = publication(null, 0, 12);
        for (var wrong : new AttachmentConversionPublication[]{
                new AttachmentConversionPublication(correct.outboxId(), "wrong", correct.key(), correct.payload(), null, 0, 12, correct.brokerTimestamp(), 0),
                new AttachmentConversionPublication(correct.outboxId(), correct.topic(), "wrong", correct.payload(), null, 0, 12, correct.brokerTimestamp(), 0),
                new AttachmentConversionPublication(correct.outboxId(), correct.topic(), correct.key(), "{}", null, 0, 12, correct.brokerTimestamp(), 0)}) {
            assertThatThrownBy(() -> transactions.recordPublished(wrong)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(stored().getStatus()).isEqualTo(AttachmentConversionOutbox.Status.PENDING);
        assertThat(stored().getPublicationVersion()).isZero();
    }

    @Test
    void 수신_확인이_실패해도_원래_위치를_잃지_않는다() {
        transactions.recordPublished(publication("topic-id", 0, 12));
        doThrow(new TransientDataAccessResourceException("test failure")).when(tasks).findByIdForUpdate(outbox.getTaskId());
        assertThatThrownBy(() -> transactions.recordPublished(publication("topic-id", 0, 13)))
                .isInstanceOf(TransientDataAccessResourceException.class);
        assertThat(stored().getRecordOffset()).isEqualTo(12);
        assertThat(stored().getPublicationVersion()).isEqualTo(1);
    }

    @Test
    void 잘못된_저장_본문은_기술적_재시도_대신_영구_실패로_처리한다() {
        jdbc.update("UPDATE attachment_conversion_outbox SET payload = ? WHERE outbox_id = ?", "{", outbox.getId());
        var invalid = new AttachmentConversionPublication(outbox.getId(), outbox.getTopic(), outbox.getTripId().toString(),
                "{", null, 0, 12, Instant.ofEpochMilli(1000), 0);
        assertThatThrownBy(() -> transactions.recordPublished(invalid))
                .isInstanceOf(InvalidAttachmentConversionMessageException.class);
        assertThat(stored().getStatus()).isEqualTo(AttachmentConversionOutbox.Status.PENDING);
    }

    @Test
    void 중복_수신을_동시에_확인해도_버전은_한_번만_증가한다() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                transactions.recordPublished(publication(null, 0, 12));
                return true;
            });
            var second = pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                transactions.recordPublished(publication(null, 0, 12));
                return true;
            });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(stored().getPublicationVersion()).isEqualTo(1);
    }

    @Test
    void 토픽_식별자를_모르면_오프셋_순서를_추론하지_않는다() {
        transactions.recordPublished(publication(null, 0, 12));
        transactions.recordPublished(publication(null, 0, 1));
        assertThat(stored().getTopicId()).isNull();
        assertThat(stored().getRecordOffset()).isEqualTo(1);
        assertThat(stored().getPublicationVersion()).isEqualTo(2);
    }

    private AttachmentConversionPublication publication(String topicId, int partition, long offset) {
        return new AttachmentConversionPublication(outbox.getId(), outbox.getTopic(), outbox.getTripId().toString(),
                outbox.getPayload(), topicId, partition, offset, Instant.ofEpochMilli(1000), 0);
    }

    private AttachmentConversionOutbox stored() {
        return outboxes.findById(outbox.getId()).orElseThrow();
    }

    private LocalDateTime aiPublishedAt() {
        return new TransactionTemplate(transactionManager).execute(status ->
                entityManager.find(TripCreationExecution.class, executionId).getProcessPublishedAt());
    }
}
