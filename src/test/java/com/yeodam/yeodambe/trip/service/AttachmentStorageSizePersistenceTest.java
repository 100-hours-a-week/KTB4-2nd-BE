package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadItemRepository;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AttachmentStorageSizePersistenceTest {
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private StoredFileRepository files;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private InitialAttachmentUploadUrlService uploads;
    @Autowired private InitialAttachmentUploadTransactionService transactions;
    @Autowired private InitialAttachmentUploadItemRepository uploadItems;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 첨부_저장_트랜잭션_종료_후_실제_용량을_다시_조회한다(boolean hasDisplay) {
        String suffix = hasDisplay ? "heic" : "jpeg";
        User user = users.saveAndFlush(new User("size-" + suffix + "@yeodam.test", "용량검증"));
        Trip trip = trips.saveAndFlush(new Trip(user.getUserId(), "용량검증", LocalDate.now(), LocalDate.now()));
        String mime = hasDisplay ? "image/heic" : "image/jpeg";
        var batch = uploads.prepareBatch(trip.getId(), user.getUserId(),
                new InitialAttachmentUploadUrlRequest(
                        1, 1, true, List.of(new InitialAttachmentUploadUrlRequest.Attachment(
                                "photo." + suffix, mime, 4L))));
        String originalKey = uploadItems.findAllByBatch_IdOrderByFileOrderAsc(batch.getId()).getFirst().getObjectKey();
        transactions.startProcessing(trip.getId(), user.getUserId(), batch.getUploadId());
        var photo = new DerivedPhotoKeys(originalKey, "analyze/" + suffix,
                "preview/" + suffix, hasDisplay ? "display/" + suffix : null,
                null, null, null, null, 4L, 100L, 50L, hasDisplay ? 200L : null);

        var saved = transactions.saveAttachments(trip.getId(), user.getUserId(), batch.getUploadId(), List.of(photo));

        var original = files.findById(saved.getFirst().getFileId()).orElseThrow();
        var attachment = attachments.findById(saved.getFirst().getId()).orElseThrow();
        assertThat(original.getOriginalSizeBytes()).isEqualTo(4L);
        assertThat(attachment.getAnalyzeSizeBytes()).isEqualTo(100L);
        assertThat(attachment.getPreviewSizeBytes()).isEqualTo(50L);
        assertThat(attachment.getDisplaySizeBytes()).isEqualTo(hasDisplay ? 200L : null);
    }
}
