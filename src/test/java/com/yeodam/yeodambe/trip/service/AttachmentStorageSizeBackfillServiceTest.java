package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AttachmentStorageSizeBackfillServiceTest {
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private StoredFileRepository files;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private AttachmentStorageSizeBackfillService backfill;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private TripAttachmentStorageClient storage;

    @Test
    void 누락된_크기를_채우고_재시도할_때_S3를_다시_읽지_않는다() {
        var fixture = create(true);
        when(storage.size(fixture.originalKey())).thenReturn(100L);
        when(storage.size(fixture.analyzeKey())).thenReturn(40L);
        when(storage.size(fixture.previewKey())).thenReturn(20L);
        when(storage.size(fixture.displayKey())).thenReturn(60L);

        backfill.backfillOne(fixture.attachmentId());

        var file = files.findById(fixture.fileId()).orElseThrow();
        var attachment = attachments.findById(fixture.attachmentId()).orElseThrow();
        assertThat(file.getOriginalSizeBytes()).isEqualTo(100L);
        assertThat(attachment.getAnalyzeSizeBytes()).isEqualTo(40L);
        assertThat(attachment.getPreviewSizeBytes()).isEqualTo(20L);
        assertThat(attachment.getDisplaySizeBytes()).isEqualTo(60L);
        var projection = attachments.findAllForStats(fixture.userId(), ProcessingStatus.COMPLETED).getFirst();
        assertThat(projection.originalSizeBytes()).isEqualTo(100L);
        assertThat(projection.analyzeSizeBytes()).isEqualTo(40L);
        assertThat(projection.previewSizeBytes()).isEqualTo(20L);
        assertThat(projection.displaySizeBytes()).isEqualTo(60L);

        clearInvocations(storage);
        backfill.backfillOne(fixture.attachmentId());
        verifyNoInteractions(storage);
    }

    @Test
    void 알려진_크기를_유지하고_없는_표시용_크기는_null로_둔다() {
        var fixture = create(false);
        jdbc.update("update files set original_size_bytes = 100 where file_id = ?", fixture.fileId());
        when(storage.size(fixture.analyzeKey())).thenReturn(40L);
        when(storage.size(fixture.previewKey())).thenReturn(20L);

        backfill.backfillOne(fixture.attachmentId());

        assertThat(files.findById(fixture.fileId()).orElseThrow().getOriginalSizeBytes()).isEqualTo(100L);
        var attachment = attachments.findById(fixture.attachmentId()).orElseThrow();
        assertThat(attachment.getAnalyzeSizeBytes()).isEqualTo(40L);
        assertThat(attachment.getPreviewSizeBytes()).isEqualTo(20L);
        assertThat(attachment.getDisplaySizeBytes()).isNull();
        verify(storage, never()).size(fixture.originalKey());
    }

    @Test
    void S3_읽기가_실패하면_모든_크기를_누락된_상태로_유지한다() {
        var fixture = create(false);
        when(storage.size(fixture.originalKey())).thenReturn(100L);
        when(storage.size(fixture.analyzeKey())).thenThrow(new IllegalStateException("S3 unavailable"));

        assertThatThrownBy(() -> backfill.backfillOne(fixture.attachmentId()))
                .isInstanceOf(IllegalStateException.class).hasMessage("S3 unavailable");

        assertThat(files.findById(fixture.fileId()).orElseThrow().getOriginalSizeBytes()).isNull();
        var attachment = attachments.findById(fixture.attachmentId()).orElseThrow();
        assertThat(attachment.getAnalyzeSizeBytes()).isNull();
        assertThat(attachment.getPreviewSizeBytes()).isNull();
        verify(storage, never()).size(fixture.previewKey());
    }

    @Test
    void 처리_중인_여행의_크기는_보충하지_않는다() {
        var fixture = create(false);
        jdbc.update("update trips set processing_status = 'PROCESSING' where trip_id = ?", fixture.tripId());

        backfill.backfillOne(fixture.attachmentId());

        verifyNoInteractions(storage);
        assertThat(files.findById(fixture.fileId()).orElseThrow().getOriginalSizeBytes()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"attachment", "file", "trip"})
    void 삭제된_데이터의_크기는_보충하지_않는다(String target) {
        var fixture = create(false);
        switch (target) {
            case "attachment" -> jdbc.update("update trip_attachments set deleted_at = current_timestamp where trip_attachment_id = ?", fixture.attachmentId());
            case "file" -> jdbc.update("update files set deleted_at = current_timestamp where file_id = ?", fixture.fileId());
            case "trip" -> jdbc.update("update trips set deleted_at = current_timestamp where trip_id = ?", fixture.tripId());
            default -> throw new IllegalArgumentException(target);
        }

        backfill.backfillOne(fixture.attachmentId());

        verifyNoInteractions(storage);
        assertThat(files.findById(fixture.fileId()).orElseThrow().getOriginalSizeBytes()).isNull();
        assertThat(attachments.findMissingStorageSizes(fixture.attachmentId() - 1, PageRequest.of(0, 100)))
                .extracting(TripAttachment::getId).doesNotContain(fixture.attachmentId());
    }

    @Test
    void ID순으로_크기_누락_행을_조회하고_삭제되거나_크기가_모두_있는_행은_제외한다() {
        var first = create(false);
        var second = create(true);
        var filled = create(false);
        var deleted = create(false);
        jdbc.update("update files set original_size_bytes = 100 where file_id = ?", filled.fileId());
        jdbc.update("update trip_attachments set analyze_size_bytes = 40, preview_size_bytes = 20 where trip_attachment_id = ?", filled.attachmentId());
        jdbc.update("update trip_attachments set deleted_at = current_timestamp where trip_attachment_id = ?", deleted.attachmentId());

        var page = attachments.findMissingStorageSizes(first.attachmentId() - 1, PageRequest.of(0, 1));
        assertThat(page).extracting(TripAttachment::getId).containsExactly(first.attachmentId());
        var next = attachments.findMissingStorageSizes(first.attachmentId(), PageRequest.of(0, 100));
        assertThat(next).extracting(TripAttachment::getId).containsExactly(second.attachmentId());
    }

    private Fixture create(boolean hasDisplay) {
        String suffix = UUID.randomUUID().toString();
        User user = users.saveAndFlush(new User(suffix + "@yeodam.test", "백필검증"));
        Trip trip = trips.saveAndFlush(new Trip(user.getUserId(), "백필검증", LocalDate.now(), LocalDate.now()));
        jdbc.update("update trips set processing_status = 'COMPLETED' where trip_id = ?", trip.getId());
        String originalKey = "backfill/" + suffix + "/original";
        String analyzeKey = "backfill/" + suffix + "/analyze";
        String previewKey = "backfill/" + suffix + "/preview";
        String displayKey = hasDisplay ? "backfill/" + suffix + "/display" : null;
        StoredFile file = files.saveAndFlush(StoredFile.uploaded(user.getUserId(), "photo.jpg", originalKey, "image/jpeg"));
        TripAttachment attachment = attachments.saveAndFlush(TripAttachment.initial(trip.getId(), file.getId(), analyzeKey, previewKey, displayKey));
        return new Fixture(user.getUserId(), trip.getId(), file.getId(), attachment.getId(), originalKey, analyzeKey, previewKey, displayKey);
    }

    private record Fixture(Long userId, Long tripId, Long fileId, Long attachmentId,
                           String originalKey, String analyzeKey, String previewKey, String displayKey) { }
}
