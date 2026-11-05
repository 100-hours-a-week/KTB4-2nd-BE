package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import jakarta.persistence.EntityManager;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class AdditionalAttachmentUploadSchemaTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private EntityManager entityManager;
    @Autowired private AdditionalAttachmentUploadBatchRepository batches;
    @Autowired private AdditionalAttachmentUploadItemRepository items;

    private Long userId;
    private Long tripId;

    @BeforeEach
    void setUp() {
        userId = users.saveAndFlush(new User(UUID.randomUUID() + "@yeodam.test", "추가회원")).getUserId();
        tripId = trips.saveAndFlush(new Trip(userId, "추가여행", LocalDate.now(), LocalDate.now())).getId();
    }

    @Test
    void 배치와_파일을_각각의_순서대로_조회한다() {
        String additionId = UUID.randomUUID().toString();
        Long second = insertBatch(UUID.randomUUID().toString(), additionId, 2);
        Long first = insertBatch(UUID.randomUUID().toString(), additionId, 1);
        insertItem(second, 1);
        insertItem(first, 2);
        insertItem(first, 1);
        insertBatch(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1);
        entityManager.clear();

        assertThat(batches.findByAdditionIdAndBatchNo(additionId, 1)).get()
                .extracting(AdditionalAttachmentUploadBatch::getId).isEqualTo(first);
        assertThat(batches.findAllByAdditionIdOrderByBatchNoAsc(additionId))
                .extracting(AdditionalAttachmentUploadBatch::getId).containsExactly(first, second);
        assertThat(items.findAllByBatch_IdOrderByFileOrderAsc(first))
                .extracting(AdditionalAttachmentUploadItem::getFileOrder).containsExactly(1, 2);
        assertThat(items.findAdditionItems(additionId))
                .extracting(item -> item.getBatch().getBatchNo() + "/" + item.getFileOrder())
                .containsExactly("1/1", "1/2", "2/1");
    }

    @Test
    void 잠금_조회는_업로드와_여행과_소유자가_일치해야_한다() {
        String uploadId = UUID.randomUUID().toString();
        insertBatch(uploadId, UUID.randomUUID().toString(), 1);
        Long otherUserId = users.saveAndFlush(new User(UUID.randomUUID() + "@yeodam.test", "다른회원")).getUserId();
        Long otherTripId = trips.saveAndFlush(new Trip(userId, "다른여행", LocalDate.now(), LocalDate.now())).getId();
        entityManager.clear();

        assertThat(batches.findForUpdate(uploadId, tripId, userId)).isPresent();
        assertThat(batches.findForUpdate(uploadId, tripId, otherUserId)).isEmpty();
        assertThat(batches.findForUpdate(uploadId, otherTripId, userId)).isEmpty();
        assertThat(batches.findForUpdate(UUID.randomUUID().toString(), tripId, userId)).isEmpty();
    }

    @Test
    void 같은_여행에서_요청한_상태의_다른_추가만_감지한다() {
        String currentAdditionId = UUID.randomUUID().toString();
        insertBatch(UUID.randomUUID().toString(), currentAdditionId, 1);
        var activeStates = List.of(AdditionalAttachmentUploadStatus.PENDING,
                AdditionalAttachmentUploadStatus.VERIFIED,
                AdditionalAttachmentUploadStatus.QUEUED,
                AdditionalAttachmentUploadStatus.PROCESSING);
        assertThat(batches.existsByTripIdAndAdditionIdNotAndStatusIn(
                tripId, currentAdditionId, activeStates)).isFalse();

        Long otherBatchId = insertBatch(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1);
        assertThat(batches.existsByTripIdAndAdditionIdNotAndStatusIn(
                tripId, currentAdditionId, activeStates)).isTrue();
        Long otherTripId = trips.saveAndFlush(new Trip(userId, "다른여행", LocalDate.now(), LocalDate.now())).getId();
        assertThat(batches.existsByTripIdAndAdditionIdNotAndStatusIn(
                otherTripId, currentAdditionId, activeStates)).isFalse();

        for (String terminal : List.of("COMPLETED", "FAILED")) {
            jdbcTemplate.update("UPDATE additional_attachment_upload_batches SET status = ? WHERE upload_batch_id = ?",
                    terminal, otherBatchId);
            entityManager.clear();
            assertThat(batches.existsByTripIdAndAdditionIdNotAndStatusIn(
                    tripId, currentAdditionId, activeStates)).isFalse();
        }
    }

    @Test
    void 배치_관계와_촬영_시각_오프셋을_포함해_엔티티를_저장한다() {
        var batch = new AdditionalAttachmentUploadBatch(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), tripId, userId, 1, 1, true);
        entityManager.persist(batch);
        var item = new AdditionalAttachmentUploadItem(batch, 1, "photo.jpg", "image/jpeg",
                1024L, "original/" + UUID.randomUUID());
        item.recordTakenAt(OffsetDateTime.parse("2026-10-07T12:00:00+09:00"));
        entityManager.persist(item);
        entityManager.flush();
        Long batchId = batch.getId();
        Long itemId = item.getId();
        entityManager.clear();

        var loaded = entityManager.find(AdditionalAttachmentUploadItem.class, itemId);
        assertThat(loaded.getBatch().getId()).isEqualTo(batchId);
        assertThat(loaded.getBatch().getStatus()).isEqualTo(AdditionalAttachmentUploadStatus.PENDING);
        assertThat(loaded.getBatch().getCreatedAt()).isNotNull();
        assertThat(loaded.getOriginalFileName()).isEqualTo("photo.jpg");
        assertThat(loaded.getSizeBytes()).isEqualTo(1024L);
        assertThat(loaded.getTripAttachmentId()).isNull();
        assertThat(loaded.getTakenAtWithOffset()).isEqualTo("2026-10-07T12:00+09:00");
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void 테이블을_마이그레이션하고_미연결_파일이_있는_배치를_저장한다() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '17' AND success = 1",
                Integer.class)).isEqualTo(1);
        String additionId = UUID.randomUUID().toString();
        Long first = insertBatch(UUID.randomUUID().toString(), additionId, 1);
        insertBatch(UUID.randomUUID().toString(), additionId, 2);
        insertItem(first, 1);
        insertItem(first, 2);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM additional_attachment_upload_batches WHERE addition_id = ?",
                Integer.class, additionId)).isEqualTo(2);
        var batch = jdbcTemplate.queryForMap(
                "SELECT status, worker_token, lease_expires_at, created_at, updated_at "
                        + "FROM additional_attachment_upload_batches WHERE upload_batch_id = ?", first);
        assertThat(batch.get("status")).isEqualTo("PENDING");
        assertThat(batch.get("worker_token")).isNull();
        assertThat(batch.get("lease_expires_at")).isNull();
        assertThat(batch.get("created_at")).isNotNull();
        assertThat(batch.get("updated_at")).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM additional_attachment_upload_items "
                        + "WHERE upload_batch_id = ? AND trip_attachment_id IS NULL",
                Integer.class, first)).isEqualTo(2);
    }

    @Test
    void 중복된_업로드_ID를_거부한다() {
        String uploadId = UUID.randomUUID().toString();
        insertBatch(uploadId, UUID.randomUUID().toString(), 1);
        assertThatThrownBy(() -> insertBatch(uploadId, UUID.randomUUID().toString(), 1))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 같은_추가의_중복된_배치_번호를_거부한다() {
        String additionId = UUID.randomUUID().toString();
        insertBatch(UUID.randomUUID().toString(), additionId, 1);
        assertThatThrownBy(() -> insertBatch(UUID.randomUUID().toString(), additionId, 1))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 같은_배치의_중복된_파일_순서를_거부한다() {
        Long batchId = insertBatch(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1);
        insertItem(batchId, 1);
        assertThatThrownBy(() -> insertItem(batchId, 1))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 두_메시지_유형과_큰_본문을_저장하고_대기_기본값을_유지한다() {
        Long batchId = insertBatch(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 2);
        String payload = "{\"content\":\"" + "x".repeat(70_000) + "\"}";
        insertOutbox(batchId, "photos_ready", payload);
        insertOutbox(batchId, "process", "{}");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '18' AND success = 1",
                Integer.class)).isEqualTo(1);
        var stored = jdbcTemplate.queryForMap("""
                SELECT payload, request_id, status, attempt_count, last_error,
                       published_at, created_at, updated_at
                FROM additional_attachment_upload_outbox
                WHERE upload_batch_id = ? AND message_type = 'photos_ready'
                """, batchId);
        assertThat(stored.get("payload")).isEqualTo(payload);
        assertThat(stored.get("request_id")).isEqualTo("outbox-test-request");
        assertThat(stored.get("status")).isEqualTo("PENDING");
        assertThat(((Number) stored.get("attempt_count")).intValue()).isZero();
        assertThat(stored.get("last_error")).isNull();
        assertThat(stored.get("published_at")).isNull();
        assertThat(stored.get("created_at")).isNotNull();
        assertThat(stored.get("updated_at")).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM additional_attachment_upload_outbox WHERE upload_batch_id = ?",
                Integer.class, batchId)).isEqualTo(2);
    }

    @Test
    void 같은_배치의_중복된_아웃박스_메시지_유형을_거부한다() {
        Long batchId = insertBatch(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1);
        insertOutbox(batchId, "photos_ready", "{}");
        assertThatThrownBy(() -> insertOutbox(batchId, "photos_ready", "{}"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 없는_배치와_지원하지_않는_유형이나_상태의_아웃박스를_거부한다() {
        assertThatThrownBy(() -> insertOutbox(Long.MAX_VALUE, "photos_ready", "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);
        Long batchId = insertBatch(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1);
        assertThatThrownBy(() -> insertOutbox(batchId, "story", "{}"))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_additional_outbox_message_type");
        insertOutbox(batchId, "photos_ready", "{}");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE additional_attachment_upload_outbox SET status = 'COMPLETED' WHERE upload_batch_id = ?",
                batchId)).isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_additional_outbox_status");
    }

    private void insertOutbox(Long batchId, String type, String payload) {
        jdbcTemplate.update("""
                INSERT INTO additional_attachment_upload_outbox
                    (upload_batch_id, message_type, payload, request_id)
                VALUES (?, ?, ?, ?)
                """, batchId, type, payload, "outbox-test-request");
    }

    private Long insertBatch(String uploadId, String additionId, int batchNo) {
        jdbcTemplate.update("""
                INSERT INTO additional_attachment_upload_batches
                    (upload_id, addition_id, trip_id, user_id, batch_no,
                     total_attachment_count, is_last_batch)
                VALUES (?, ?, ?, ?, ?, 2, ?)
                """, uploadId, additionId, tripId, userId, batchNo, batchNo == 2);
        return jdbcTemplate.queryForObject(
                "SELECT upload_batch_id FROM additional_attachment_upload_batches WHERE upload_id = ?",
                Long.class, uploadId);
    }

    private void insertItem(Long batchId, int order) {
        jdbcTemplate.update("""
                INSERT INTO additional_attachment_upload_items
                    (upload_batch_id, file_order, original_file_name, content_type, size_bytes, object_key)
                VALUES (?, ?, 'same.jpg', 'image/jpeg', 1024, ?)
                """, batchId, order, "original/" + UUID.randomUUID());
    }
}
