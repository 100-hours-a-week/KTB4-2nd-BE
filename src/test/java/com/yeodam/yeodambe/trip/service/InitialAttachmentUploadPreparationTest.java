package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadUrlRequest;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InitialAttachmentUploadPreparationTest {
    @Autowired private InitialAttachmentUploadUrlService service;
    @Autowired private InitialAttachmentUploadBatchRepository batches;
    @Autowired private InitialAttachmentUploadItemRepository items;
    @Autowired private TripRepository trips;
    @Autowired private UserRepository users;
    @Autowired private StoredFileRepository files;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User owner;
    private Trip trip;

    @BeforeEach
    void setUp() {
        owner = users.save(new User(UUID.randomUUID() + "@yeodam.test", "배치회원"));
        trip = trips.save(new Trip(owner.getUserId(), "배치여행", LocalDate.now(), LocalDate.now()));
    }

    @Test
    void createsFirstBatchAndReusesItsIdAndKeysOnIdenticalRequest() {
        var request = request(1, 3, false, firstFiles());
        InitialAttachmentUploadBatch first = prepare(request);
        List<String> originalKeys = keys(first);

        InitialAttachmentUploadBatch repeated = prepare(request);

        assertThat(first.getId()).isNotNull();
        assertThat(first.getStatus()).isEqualTo(InitialAttachmentUploadStatus.PENDING);
        assertThat(repeated.getId()).isEqualTo(first.getId());
        assertThat(repeated.getUploadId()).isEqualTo(first.getUploadId());
        assertThat(keys(repeated)).containsExactlyElementsOf(originalKeys);
        assertThat(originalKeys).hasSize(2).doesNotHaveDuplicates();
        assertThat(originalKeys).allSatisfy(key ->
                assertThat(key).startsWith("trip-uploads/" + first.getExecutionId() + "/original/"));
        assertThat(batches.findAllByExecutionIdOrderByBatchNoAsc(first.getExecutionId())).hasSize(1);
        assertThat(attachments.findAllByTripIdAndDeletedAtIsNull(trip.getId())).isEmpty();
    }

    @Test
    void rejectsChangedMetadataOrBatchDeclarationOnSameBatchNumber() {
        InitialAttachmentUploadBatch first = prepare(request(1, 3, false, firstFiles()));
        List<String> originalKeys = keys(first);
        var firstFile = firstFiles().getFirst();
        var secondFile = firstFiles().getLast();
        List<InitialAttachmentUploadUrlRequest> changedRequests = List.of(
                request(1, 4, false, firstFiles()),
                request(1, 3, true, firstFiles()),
                request(1, 3, false, List.of(secondFile, firstFile)),
                request(1, 3, false, List.of(file("changed.jpg", 1024), secondFile)),
                request(1, 3, false, List.of(file("first.jpg", 1025), secondFile)),
                request(1, 3, false, List.of(
                        new InitialAttachmentUploadUrlRequest.Attachment("first.jpg", "image/png", 1024L), secondFile)));

        for (var changed : changedRequests) {
            assertThatThrownBy(() -> prepare(changed))
                    .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        }

        assertThat(keys(first)).containsExactlyElementsOf(originalKeys);
        assertThat(batches.findAllByExecutionIdOrderByBatchNoAsc(first.getExecutionId())).hasSize(1);
    }

    @Test
    void acceptsNextBatchOnlyAfterPreviousCompletionAndKeepsExecutionId() {
        InitialAttachmentUploadBatch first = prepare(request(1, 3, false, firstFiles()));
        var secondRequest = request(2, 3, true, List.of(file("third.jpg", 4096)));
        assertThatThrownBy(() -> prepare(secondRequest))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);

        markCompleted(first);
        assertThatThrownBy(() -> prepare(request(3, 3, true, secondRequest.attachments())))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        assertThatThrownBy(() -> prepare(request(2, 4, false, secondRequest.attachments())))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);

        InitialAttachmentUploadBatch second = prepare(secondRequest);

        assertThat(second.getExecutionId()).isEqualTo(first.getExecutionId());
        assertThat(second.getUploadId()).isNotEqualTo(first.getUploadId());
        assertThat(second.getLastBatch()).isTrue();
        assertThat(items.findAllByBatch_ExecutionId(first.getExecutionId())).hasSize(3);
        assertThat(batches.findAllByExecutionIdOrderByBatchNoAsc(first.getExecutionId()))
                .extracting(InitialAttachmentUploadBatch::getBatchNo).containsExactly(1, 2);
    }

    @Test
    void rejectsIncorrectFinalCountWithoutCreatingNewBatch() {
        assertThatThrownBy(() -> prepare(request(1, 3, true, firstFiles())))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThat(batches.findFirstByTripIdAndUserIdOrderByIdDesc(trip.getId(), owner.getUserId())).isEmpty();

        InitialAttachmentUploadBatch first = prepare(request(1, 3, false, firstFiles()));
        markCompleted(first);
        assertThatThrownBy(() -> prepare(request(2, 3, false, List.of(file("third.jpg", 4096)))))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThatThrownBy(() -> prepare(request(2, 3, true, firstFiles())))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThat(batches.findAllByExecutionIdOrderByBatchNoAsc(first.getExecutionId())).hasSize(1);
    }

    @Test
    void refusesWrongOwnerDeletedTripOrNonProcessingTrip() {
        var request = request(1, 1, true, List.of(file("first.jpg", 1024)));
        User other = users.save(new User(UUID.randomUUID() + "@yeodam.test", "다른회원"));
        assertThatThrownBy(() -> service.prepareBatch(trip.getId(), other.getUserId(), request))
                .isInstanceOf(TripNotFoundException.class);

        jdbcTemplate.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", trip.getId());
        assertThatThrownBy(() -> prepare(request))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);

        jdbcTemplate.update("UPDATE trips SET deleted_at = CURRENT_TIMESTAMP(6) WHERE trip_id = ?", trip.getId());
        assertThatThrownBy(() -> prepare(request)).isInstanceOf(TripNotFoundException.class);
        assertThat(batches.findFirstByTripIdAndUserIdOrderByIdDesc(trip.getId(), owner.getUserId())).isEmpty();
    }

    @Test
    void refusesExistingAttachmentsFirstBatchNumberOtherThanOneAndRequestsAfterFinalBatch() {
        assertThatThrownBy(() -> prepare(request(2, 1, true, List.of(file("first.jpg", 1024)))))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        StoredFile original = files.saveAndFlush(StoredFile.uploaded(owner.getUserId(), "existing.jpg", "existing/key", "image/jpeg"));
        TripAttachment existing = attachments.saveAndFlush(TripAttachment.initial(
                trip.getId(), original.getId(), "existing/analyze", "existing/preview"));
        assertThatThrownBy(() -> prepare(request(1, 1, true, List.of(file("first.jpg", 1024)))))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);

        attachments.deleteById(existing.getId());
        InitialAttachmentUploadBatch last = prepare(request(1, 1, true, List.of(file("first.jpg", 1024))));
        markCompleted(last);
        assertThatThrownBy(() -> prepare(request(2, 1, true, List.of(file("second.jpg", 1024)))))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        assertThatThrownBy(() -> prepare(request(1, 1, true, List.of(file("first.jpg", 1024)))))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
    }

    @Test
    void simultaneousIdenticalRequestsCreateOnlyOneBatchAndOneFileList() throws Exception {
        var request = request(1, 3, false, firstFiles());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var action = (java.util.concurrent.Callable<InitialAttachmentUploadBatch>) () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                return prepare(request);
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            InitialAttachmentUploadBatch a = first.get(20, TimeUnit.SECONDS);
            InitialAttachmentUploadBatch b = second.get(20, TimeUnit.SECONDS);

            assertThat(a.getId()).isEqualTo(b.getId());
            assertThat(a.getUploadId()).isEqualTo(b.getUploadId());
            assertThat(batches.findAllByExecutionIdOrderByBatchNoAsc(a.getExecutionId())).hasSize(1);
            assertThat(items.findAllByBatch_ExecutionId(a.getExecutionId())).hasSize(2);
        }
    }

    private InitialAttachmentUploadBatch prepare(InitialAttachmentUploadUrlRequest request) {
        return service.prepareBatch(trip.getId(), owner.getUserId(), request);
    }

    private List<String> keys(InitialAttachmentUploadBatch batch) {
        return items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId()).stream()
                .map(InitialAttachmentUploadItem::getObjectKey).toList();
    }

    private void markCompleted(InitialAttachmentUploadBatch batch) {
        jdbcTemplate.update("UPDATE initial_attachment_upload_batches SET status = 'COMPLETED' WHERE upload_batch_id = ?", batch.getId());
    }

    private List<InitialAttachmentUploadUrlRequest.Attachment> firstFiles() {
        return List.of(file("first.jpg", 1024), file("second.jpg", 2048));
    }

    private InitialAttachmentUploadUrlRequest.Attachment file(String name, long bytes) {
        return new InitialAttachmentUploadUrlRequest.Attachment(name, "image/jpeg", bytes);
    }

    private InitialAttachmentUploadUrlRequest request(
            int number, int total, boolean complete, List<InitialAttachmentUploadUrlRequest.Attachment> files) {
        return new InitialAttachmentUploadUrlRequest(number, total, complete, files);
    }
}
