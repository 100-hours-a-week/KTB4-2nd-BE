package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.*;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;
import com.yeodam.yeodambe.trip.client.*;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.request.*;
import com.yeodam.yeodambe.user.entity.*;
import com.yeodam.yeodambe.user.repository.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"app.outbox.receipt-listener-enabled=false"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AdditionalAttachmentCdcIntegrationTest {
    @Autowired private AdditionalAttachmentUploadUrlService urls;
    @Autowired private AdditionalAttachmentUploadCompletionService completion;
    @Autowired private AdditionalAttachmentUploadTransactionService transactions;
    @Autowired private AdditionalAttachmentUploadOutboxService outboxService;
    @Autowired private AdditionalAttachmentUploadOutboxRepository outboxes;
    @Autowired private AdditionalAttachmentExecutionRepository executionRepository;
    @Autowired private TripRepository trips;
    @Autowired private TripRegionRepository regions;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private TripDetailPlaceRepository places;
    @Autowired private StoredFileRepository files;
    @Autowired private UserRepository users;
    @Autowired private UserStatsRepository stats;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transaction;
    @MockitoBean private TripAttachmentStorageClient storage;
    @MockitoBean private TripAttachmentDerivativeService derivatives;
    @MockitoBean private KakaoLocalClient kakao;
    private Long userId;
    private Long tripId;
    private Long oldPhotoId;
    private Long oldPlaceId;
    private String additionId;
    private String uploadId;
    @BeforeEach
    void setUp() {
        User owner = users.save(new User(UUID.randomUUID() + "@yeodam.test", "재분석회원"));
        userId = owner.getUserId();
        stats.save(new UserStats(owner));
        Trip trip = trips.save(new Trip(userId, "재분석 여행", LocalDate.now(), LocalDate.now()));
        tripId = trip.getId();
        jdbc.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", tripId);
        regions.save(new TripRegion(trip, "11000", "서울특별시", BigDecimal.ONE, BigDecimal.TEN));
        StoredFile file = StoredFile.uploaded(userId, "old.jpg", "old/" + UUID.randomUUID(), "image/jpeg");
        file.storageSize(12L);
        file = files.save(file);
        var place = places.save(TripDetailPlace.fromAnalysis(tripId, 1, "기존 장소", BigDecimal.ONE,
                BigDecimal.TEN, LocalDateTime.now(), LocalDateTime.now(), "old-preview"));
        oldPlaceId = place.getId();
        TripAttachment old = TripAttachment.initial(tripId, file.getId(), "old-analyze", "old-preview");
        old.storageSizes(8L, 4L, null);
        old.originalMetadata(OffsetDateTime.parse("2026-10-01T10:00:00+09:00"), BigDecimal.ONE, BigDecimal.TEN, "camera");
        old.classify(oldPlaceId, RegionOrigin.EXIF, LocalDateTime.now(), BigDecimal.ONE, BigDecimal.TEN, 80);
        oldPhotoId = attachments.save(old).getId();
        when(storage.createUploadUrl(anyString(), anyString(), any())).thenAnswer(c -> "https://example.test/" + c.getArgument(0));
        when(storage.size(anyString())).thenReturn(12L);
        when(storage.open(anyString())).thenAnswer(c -> new ByteArrayInputStream(new byte[]{(byte)255, (byte)216, (byte)255}));
        when(derivatives.createAll(anyString(), anyList(), anyList())).thenAnswer(call -> {
            List<String> keys = call.getArgument(1);
            return CompletableFuture.completedFuture(keys.stream().map(key -> new DerivedPhotoKeys(
                    key, key + "-analyze", key + "-preview", null,
                    OffsetDateTime.parse("2026-10-07T12:00:00+09:00"), BigDecimal.ONE, BigDecimal.TEN,
                    "camera", 12L, 8L, 4L, null)).toList());
        });
        when(kakao.lookup(any(), any())).thenReturn(new KakaoLocalClient.LookupResult("새 장소", "서울", KakaoLocalClient.Failure.NONE));
        additionId = UUID.randomUUID().toString();
    }


    @Test
    void existingOutboxCreatesProcessOnlyAfterPhotosReadyConfirmation() {
        convertBatch(1, 1, true);
        var ready = outboxes.findAll().stream().filter(value -> tripId.equals(value.getTripId())).toList();
        assertThat(ready).hasSize(1);
        var event = ready.getFirst();
        assertThat(event.getMessageType()).isEqualTo("photos_ready");
        outboxService.recordPublished(event.getId(), receipt(event));
        var process = outboxes.findAll().stream().filter(value -> tripId.equals(value.getTripId())
                && "process".equals(value.getMessageType())).findFirst().orElseThrow();
        outboxService.recordPublished(event.getId(), receipt(event));
        assertThat(outboxes.findAll().stream().filter(value -> tripId.equals(value.getTripId()))).hasSize(2);
        var appended = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.MILLIS);
        outboxService.recordPublished(process.getId(), new OutboxReceipt(
                process.getTopic(), tripId.toString(), process.getPayload(), appended));
        assertThat(jdbc.queryForObject("SELECT UNIX_TIMESTAMP(process_published_at) * 1000 FROM additional_attachment_executions WHERE execution_id = ?",
                BigDecimal.class, additionId).longValueExact()).isEqualTo(appended.toEpochMilli());
        var first = executionRepository.findById(additionId).orElseThrow().getProcessPublishedAt();
        outboxService.recordPublished(process.getId(), receipt(process));
        assertThat(executionRepository.findById(additionId).orElseThrow().getProcessPublishedAt()).isEqualTo(first);
        assertThat(attachments.findById(oldPhotoId).orElseThrow().getDeletedAt()).isNull();
    }

    @AfterEach
    void finishExecution() {
        executionRepository.findById(additionId).filter(execution -> !execution.isTerminal())
                .ifPresent(execution -> transactions.failExecution(additionId, "TEST_FINISHED"));
    }

    @Test
    void waitsForAllBatchesAndConcurrentReceiptsCreateOneProcess() throws Exception {
        convertBatch(1, 2, false);
        convertBatch(2, 2, true);
        var ready = outboxes.findAll().stream().filter(value -> tripId.equals(value.getTripId())).toList();
        assertThat(ready).hasSize(2);
        assertThat(executionRepository.findById(additionId).orElseThrow().getProcessOutboxId()).isNull();
        var waiting = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var tasks = ready.stream().map(event -> workers.submit(() -> {
                waiting.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                outboxService.recordPublished(event.getId(), receipt(event));
                return null;
            })).toList();
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }
        var process = outboxes.findAll().stream().filter(value -> tripId.equals(value.getTripId())
                && "process".equals(value.getMessageType())).toList();
        assertThat(process).hasSize(1);
        assertThat(executionRepository.findById(additionId).orElseThrow().getProcessOutboxId())
                .isEqualTo(process.getFirst().getId());
    }

    @Test
    void oneUnconfirmedBatchPreventsProcessCreation() {
        convertBatch(1, 2, false);
        convertBatch(2, 2, true);
        var ready = outboxes.findAll().stream().filter(value -> tripId.equals(value.getTripId())).toList();
        outboxService.recordPublished(ready.getFirst().getId(), receipt(ready.getFirst()));
        assertThat(executionRepository.findById(additionId).orElseThrow().getProcessOutboxId()).isNull();
        assertThat(outboxes.findAll().stream().filter(value -> tripId.equals(value.getTripId()))).hasSize(2);

        outboxService.recordPublished(ready.getLast().getId(), receipt(ready.getLast()));
        assertThat(executionRepository.findById(additionId).orElseThrow().getProcessOutboxId()).isNotNull();
        assertThat(outboxes.findAll().stream().filter(value -> tripId.equals(value.getTripId()))).hasSize(3);
    }

    @Test
    void databaseFailureRollsBackConfirmationAndProcessTogether() {
        convertBatch(1, 1, true);
        var ready = outboxes.findAll().stream().filter(value -> tripId.equals(value.getTripId()))
                .findFirst().orElseThrow();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            outboxService.recordPublished(ready.getId(), receipt(ready));
            jdbc.update("""
                    INSERT INTO additional_attachment_upload_outbox
                    (upload_batch_id, message_type, payload, request_id)
                    VALUES (?, 'photos_ready', '{}', 'duplicate')
                    """, ready.getUploadBatchId());
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(outboxes.findById(ready.getId()).orElseThrow().getPublishedAt()).isNull();
        assertThat(outboxes.findById(ready.getId()).orElseThrow().getStatus())
                .isEqualTo(AdditionalAttachmentUploadOutbox.Status.PENDING);
        assertThat(executionRepository.findById(additionId).orElseThrow().getProcessOutboxId()).isNull();
        assertThat(outboxes.findAll().stream().filter(value -> tripId.equals(value.getTripId()))).hasSize(1);
        assertThat(attachments.findById(oldPhotoId).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    void invalidPayloadAndLateReceiptCannotStartTerminalExecution() {
        convertBatch(1, 1, true);
        var ready = outboxes.findAll().stream().filter(value -> tripId.equals(value.getTripId()))
                .findFirst().orElseThrow();
        assertThatThrownBy(() -> outboxService.recordPublished(ready.getId(), new OutboxReceipt(
                ready.getTopic(), tripId.toString(), "wrong-payload", Instant.now())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(outboxes.findById(ready.getId()).orElseThrow().getPublishedAt()).isNull();
        transactions.failExecution(additionId, "TEST_TERMINATED");
        outboxService.recordPublished(ready.getId(), receipt(ready));
        assertThat(executionRepository.findById(additionId).orElseThrow().isTerminal()).isTrue();
        assertThat(executionRepository.findById(additionId).orElseThrow().getProcessOutboxId()).isNull();
        assertThat(attachments.findById(oldPhotoId).orElseThrow().getDeletedAt()).isNull();
    }

    private void convertBatch(int number, int total, boolean last) {
        uploadId = urls.issueUploadUrls(tripId, userId, request(number, total, last)).uploadId();
        completion.complete(tripId, userId, new AdditionalAttachmentUploadCompleteRequest(uploadId));
        var claim = transactions.claimBatch().orElseThrow();
        var item = claim.photos().getFirst();
        var keys = transactions.startPhotoAttempt(claim, item.itemId());
        transactions.savePhotoSuccess(claim, item.itemId(), new DerivedPhotoKeys(
                item.objectKey(), keys.get(0), keys.get(1), null,
                OffsetDateTime.parse("2026-10-07T12:00:00+09:00"), BigDecimal.ONE, BigDecimal.TEN,
                "camera", 12L, 8L, 4L, null));
        transactions.completeBatch(claim);
    }

    private OutboxReceipt receipt(
            AdditionalAttachmentUploadOutbox event
    ) {
        return new OutboxReceipt(
                event.getTopic(), tripId.toString(), event.getPayload(), Instant.now());
    }

    private AdditionalAttachmentUploadUrlRequest request(int batchNo, int total, boolean complete) {
        return new AdditionalAttachmentUploadUrlRequest(additionId, batchNo, total, complete,
                List.of(new AdditionalAttachmentUploadUrlRequest.Attachment("new.jpg", "image/jpeg", 12L)));
    }
}
