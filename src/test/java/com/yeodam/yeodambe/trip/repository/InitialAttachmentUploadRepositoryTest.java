package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class InitialAttachmentUploadRepositoryTest {
    @Autowired private InitialAttachmentUploadBatchRepository batches;
    @Autowired private InitialAttachmentUploadItemRepository items;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private StoredFileRepository files;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User owner;
    private Trip trip;

    @BeforeEach
    void setUp() {
        owner = users.save(new User(UUID.randomUUID() + "@yeodam.test", "업로드회원"));
        trip = trips.save(new Trip(owner.getUserId(), "업로드여행", LocalDate.now(), LocalDate.now()));
    }

    @Test
    void 마이그레이션된_배치를_저장하고_동일한_이름의_파일을_요청순으로_반환한다() {
        String executionId = UUID.randomUUID().toString();
        InitialAttachmentUploadBatch first = batches.save(batch(executionId, 1, false));
        InitialAttachmentUploadBatch last = batches.save(batch(executionId, 2, true));
        items.save(item(first, 2, "original/second"));
        items.save(item(first, 1, "original/first"));
        entityManager.flush();
        entityManager.clear();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '9' AND success = 1",
                Integer.class)).isEqualTo(1);
        InitialAttachmentUploadBatch loaded = batches.findByExecutionIdAndBatchNo(executionId, 1)
                .orElseThrow();
        assertThat(loaded.getUploadId()).isEqualTo(first.getUploadId());
        assertThat(loaded.getStatus()).isEqualTo(InitialAttachmentUploadStatus.PENDING);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(batches.findFirstByTripIdAndUserIdOrderByIdDesc(trip.getId(), owner.getUserId()))
                .get().extracting(InitialAttachmentUploadBatch::getId).isEqualTo(last.getId());
        assertThat(batches.findAllByExecutionIdOrderByBatchNoAsc(executionId))
                .extracting(InitialAttachmentUploadBatch::getBatchNo).containsExactly(1, 2);
        List<InitialAttachmentUploadItem> ordered = items.findAllByBatch_IdOrderByFileOrderAsc(first.getId());
        assertThat(ordered).extracting(InitialAttachmentUploadItem::getObjectKey)
                .containsExactly("original/first", "original/second");
        assertThat(ordered).extracting(InitialAttachmentUploadItem::getOriginalFileName)
                .containsExactly("same.jpg", "same.jpg");
        assertThat(ordered).allSatisfy(value -> {
            assertThat(value.getSizeBytes()).isEqualTo(1024L);
            assertThat(value.getCreatedAt()).isNotNull();
            assertThat(value.getTripAttachmentId()).isNull();
        });
    }

    @Test
    void 잠금_조회에는_일치하는_업로드_ID와_여행과_소유자가_필요하다() {
        InitialAttachmentUploadBatch saved = batches.saveAndFlush(batch(UUID.randomUUID().toString(), 1, false));
        User otherUser = users.save(new User(UUID.randomUUID() + "@yeodam.test", "다른회원"));
        Trip otherTrip = trips.save(new Trip(owner.getUserId(), "다른여행", LocalDate.now(), LocalDate.now()));
        entityManager.flush();
        entityManager.clear();

        assertThat(batches.findForUpdate(saved.getUploadId(), trip.getId(), owner.getUserId())).isPresent();
        assertThat(batches.findForUpdate(saved.getUploadId(), trip.getId(), otherUser.getUserId())).isEmpty();
        assertThat(batches.findForUpdate(saved.getUploadId(), otherTrip.getId(), owner.getUserId())).isEmpty();
        assertThat(batches.findForUpdate(UUID.randomUUID().toString(), trip.getId(), owner.getUserId())).isEmpty();
    }

    @Test
    void 중복된_공개_업로드_ID를_거부한다() {
        InitialAttachmentUploadBatch first = batches.saveAndFlush(batch(UUID.randomUUID().toString(), 1, false));
        InitialAttachmentUploadBatch duplicate = new InitialAttachmentUploadBatch(
                first.getUploadId(), UUID.randomUUID().toString(), trip.getId(), owner.getUserId(), 1, 2, false);

        assertThatThrownBy(() -> batches.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 같은_작업의_중복_배치_번호를_거부한다() {
        String executionId = UUID.randomUUID().toString();
        batches.saveAndFlush(batch(executionId, 1, false));

        assertThatThrownBy(() -> batches.saveAndFlush(batch(executionId, 1, false)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 같은_배치의_중복_파일_순서를_거부한다() {
        InitialAttachmentUploadBatch saved = batches.saveAndFlush(batch(UUID.randomUUID().toString(), 1, false));
        items.saveAndFlush(item(saved, 1, "original/first"));

        assertThatThrownBy(() -> items.saveAndFlush(item(saved, 1, "original/second")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 첨부를_삭제하면_항목의_참조만_제거한다() {
        InitialAttachmentUploadBatch saved = batches.saveAndFlush(batch(UUID.randomUUID().toString(), 1, false));
        InitialAttachmentUploadItem savedItem = items.saveAndFlush(item(saved, 1, "original/first"));
        StoredFile original = files.saveAndFlush(StoredFile.uploaded(owner.getUserId(), "same.jpg", "original/first", "image/jpeg"));
        TripAttachment attachment = attachments.saveAndFlush(TripAttachment.initial(
                trip.getId(), original.getId(), "analyze/first", "preview/first"));
        jdbcTemplate.update(
                "UPDATE initial_attachment_upload_items SET trip_attachment_id = ? WHERE upload_item_id = ?",
                attachment.getId(), savedItem.getId());
        entityManager.clear();
        assertThat(items.findById(savedItem.getId()).orElseThrow().getTripAttachmentId())
                .isEqualTo(attachment.getId());

        attachments.deleteAllByIdInBatch(List.of(attachment.getId()));
        entityManager.clear();

        InitialAttachmentUploadItem remaining = items.findById(savedItem.getId()).orElseThrow();
        assertThat(remaining.getTripAttachmentId()).isNull();
        assertThat(remaining.getObjectKey()).isEqualTo("original/first");
        assertThat(batches.existsById(saved.getId())).isTrue();
        assertThat(files.existsById(original.getId())).isTrue();
    }

    private InitialAttachmentUploadBatch batch(String executionId, int batchNo, boolean lastBatch) {
        return new InitialAttachmentUploadBatch(UUID.randomUUID().toString(), executionId,
                trip.getId(), owner.getUserId(), batchNo, 2, lastBatch);
    }

    private InitialAttachmentUploadItem item(InitialAttachmentUploadBatch batch, int order, String objectKey) {
        return new InitialAttachmentUploadItem(batch, order, "same.jpg", "image/jpeg", 1024L, objectKey);
    }
}
