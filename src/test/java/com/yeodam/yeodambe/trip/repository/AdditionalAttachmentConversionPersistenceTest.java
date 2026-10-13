package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class AdditionalAttachmentConversionPersistenceTest {
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 12, 0);

    @Test
    void migrationAndMappingsPreserveBatchLeaseAndRetryAcrossReloads() {
        var batch = persistBatch();
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '21' AND success = true", Integer.class))
                .isEqualTo(1);
        batch.markVerified();
        batch.claimConversion("first", NOW.plusMinutes(10));
        batch.renewConversion("first", NOW.plusMinutes(20));
        entityManager.flush();
        entityManager.clear();
        batch = entityManager.find(AdditionalAttachmentUploadBatch.class, batch.getId());
        assertThat(batch.getWorkerToken()).isEqualTo("first");
        assertThat(batch.getLeaseExpiresAt()).isEqualTo(NOW.plusMinutes(20));
        batch.retryConversion("first", NOW.plusSeconds(10));
        entityManager.flush();
        entityManager.clear();
        batch = entityManager.find(AdditionalAttachmentUploadBatch.class, batch.getId());
        assertThat(batch.getStatus()).isEqualTo(AdditionalAttachmentUploadStatus.VERIFIED);
        assertThat(batch.getWorkerToken()).isNull();
        assertThat(batch.getLeaseExpiresAt()).isNull();
        assertThat(batch.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
    }

    @Test
    void preservesLargePhotoResultAndCleanupAfterFailureAndSuccessfulRetry() {
        var batch = persistBatch();
        var item = new AdditionalAttachmentUploadItem(batch, 1, "photo.jpg", "image/jpeg", 1024L, "original");
        entityManager.persist(item);
        entityManager.flush();
        assertThat(item.getConversionAttempts()).isZero();
        String cleanup = "[\"temporary-preview\"]";
        item.startConversion(NOW);
        item.recordCleanup(cleanup, NOW.plusMinutes(10));
        item.conversionFailed(NOW.plusSeconds(10));
        entityManager.flush();
        entityManager.clear();
        item = entityManager.find(AdditionalAttachmentUploadItem.class, item.getId());
        assertThat(item.getConversionAttempts()).isEqualTo(1);
        assertThat(item.getConversionStartedAt()).isNull();
        assertThat(item.getConversionNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
        String derived = "{\"data\":\"" + "x".repeat(70_000) + "\"}";
        item.startConversion(NOW.plusSeconds(10));
        item.conversionCompleted(derived);
        entityManager.flush();
        entityManager.clear();
        item = entityManager.find(AdditionalAttachmentUploadItem.class, item.getId());
        assertThat(item.getConversionAttempts()).isEqualTo(2);
        assertThat(item.getConversionStartedAt()).isNull();
        assertThat(item.getConversionNextAttemptAt()).isNull();
        assertThat(item.getDerivedJson()).isEqualTo(derived);
        assertThat(item.getCleanupJson()).isEqualTo(cleanup);
        assertThat(item.getCleanupStartedAt()).isEqualTo(NOW.plusMinutes(10));
    }

    private AdditionalAttachmentUploadBatch persistBatch() {
        var user = new User(UUID.randomUUID() + "@yeodam.test", "conversion");
        entityManager.persist(user);
        var trip = new Trip(user.getUserId(), "conversion", LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 13));
        entityManager.persist(trip);
        var batch = new AdditionalAttachmentUploadBatch(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), trip.getId(), user.getUserId(), 1, 1, true);
        entityManager.persist(batch);
        return batch;
    }
}
