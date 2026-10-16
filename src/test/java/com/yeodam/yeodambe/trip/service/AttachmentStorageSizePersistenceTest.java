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
    @Autowired private com.yeodam.yeodambe.user.repository.UserStatsRepository stats;
    @Autowired private TripRepository trips;
    @Autowired private StoredFileRepository files;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private InitialAttachmentUploadTransactionService transactions;
    @Autowired private InitialAttachmentUploadItemRepository uploadItems;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 첨부_저장_트랜잭션_종료_후_실제_용량을_다시_조회한다(boolean hasDisplay) {
        String suffix = hasDisplay ? "heic" : "jpeg";
        User user = users.saveAndFlush(new User("size-" + suffix + "@yeodam.test", "용량검증"));
        stats.saveAndFlush(new com.yeodam.yeodambe.user.entity.UserStats(user));
        Trip trip = trips.saveAndFlush(new Trip(user.getUserId(), "용량검증", LocalDate.now(), LocalDate.now()));
        String mime = hasDisplay ? "image/heic" : "image/jpeg";
        var batch = transactions.prepareBatch(trip.getId(), user.getUserId(),
                new InitialAttachmentUploadUrlRequest(
                        1, 1, true, List.of(new InitialAttachmentUploadUrlRequest.Attachment(
                                "photo." + suffix, mime, 4L))));
        String originalKey = batch.photos().getFirst().objectKey();
        transactions.startProcessing(trip.getId(), user.getUserId(), batch.uploadId());
        var claim = transactions.claimBatch().orElseThrow();
        Long itemId = batch.photos().getFirst().itemId();
        List<String> keys = transactions.startPhotoAttempt(claim, itemId);
        var photo = new DerivedPhotoKeys(originalKey, keys.get(0),
                keys.get(1), hasDisplay ? keys.get(2) : null,
                null, null, null, null, 4L, 100L, 50L, hasDisplay ? 200L : null);

        transactions.savePhotoSuccess(claim, itemId, photo);
        transactions.completeBatch(claim);
        var savedItem = uploadItems.findById(itemId).orElseThrow();
        var attachment = attachments.findById(savedItem.getTripAttachmentId()).orElseThrow();
        var original = files.findById(attachment.getFileId()).orElseThrow();
        transactions.failExecution(batch.executionId(), "TEST_FINISHED");
        assertThat(original.getOriginalSizeBytes()).isEqualTo(4L);
        assertThat(attachment.getAnalyzeSizeBytes()).isEqualTo(100L);
        assertThat(attachment.getPreviewSizeBytes()).isEqualTo(50L);
        assertThat(attachment.getDisplaySizeBytes()).isEqualTo(hasDisplay ? 200L : null);
    }
}
