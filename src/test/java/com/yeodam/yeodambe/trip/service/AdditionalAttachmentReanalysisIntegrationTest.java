package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;
import java.time.Instant;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.websocket.service.WebSocketEventPublisher;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEvent;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusNotification;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.yeodam.yeodambe.common.exception.*;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.client.*;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.request.*;
import com.yeodam.yeodambe.user.entity.*;
import com.yeodam.yeodambe.user.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, AdditionalAttachmentReanalysisIntegrationTest.MongoConfig.class})
class AdditionalAttachmentReanalysisIntegrationTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class MongoConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails isolatedMongo() {
            return () -> new com.mongodb.ConnectionString(
                    com.yeodam.yeodambe.SharedTestMongo.connectionString("additional_reanalysis_test"));
        }
    }

    @Autowired private com.yeodam.yeodambe.story.repository.StoryContentRepository storyContents;

    @Autowired private AdditionalAttachmentKafkaResultService kafkaResults;
    @Autowired private AdditionalAttachmentExecutionRepository executionRepository;
    @Autowired private TripProcessingStatusService processingStatus;
    @Autowired private TripWithdrawalService tripWithdrawal;
    @Autowired private TripObjectCleanupService objectCleanup;
    @Autowired private AdditionalAttachmentUploadItemRepository additionalItems;
    @Autowired private AdditionalAttachmentUploadUrlService urls;
    @Autowired private AdditionalAttachmentUploadCompletionService completion;
    @Autowired private AdditionalAttachmentUploadOutboxService outboxService;
    @Autowired private AdditionalAttachmentUploadOutboxRepository outboxes;
    @Autowired private ObjectMapper applicationMapper;
    @Autowired private com.yeodam.yeodambe.story.service.StoryReadService storyReadService;
    @Autowired private AdditionalAttachmentUploadTransactionService transactions;
    @Autowired private AdditionalAttachmentAnalysisPreparationService preparation;
    @Autowired private AdditionalAttachmentAnalysisResultService results;
    @Autowired private AdditionalAttachmentAnalysisResultTransactionService resultTransactions;
    @Autowired private TripAttachmentDeletionService deletion;
    @Autowired private TripService tripService;
    @Autowired private TripDeletionService tripDeletion;
    @Autowired private TripRepository trips;
    @Autowired private TripRegionRepository regions;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private TripDetailPlaceRepository places;
    @Autowired private StoredFileRepository files;
    @Autowired private UserRepository users;
    @Autowired private UserStatsRepository stats;
    @Autowired private InitialAttachmentUploadBatchRepository initialBatches;
    @Autowired private InitialAttachmentUploadItemRepository initialItems;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private TripAttachmentStorageClient storage;
    @MockitoBean private TripAttachmentDerivativeService derivatives;
    @MockitoBean private KakaoLocalClient kakao;
    @MockitoBean private WebSocketEventPublisher notificationPublisher;
    @Autowired private TransactionTemplate transaction;
    private Long userId;
    private Long tripId;
    private Long oldPhotoId;
    private Long oldPlaceId;
    private String additionId;
    private String uploadId;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        User owner = users.save(new User(UUID.randomUUID() + "@yeodam.test", "재분석회원"));
        userId = owner.getUserId(); stats.save(new UserStats(owner));
        Trip trip = trips.save(new Trip(userId, "재분석 여행", LocalDate.now(), LocalDate.now()));
        tripId = trip.getId();
        jdbc.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", tripId);
        regions.save(new TripRegion(trip, "11000", "서울특별시", BigDecimal.ONE, BigDecimal.TEN));
        StoredFile file = StoredFile.uploaded(userId, "old.jpg", "old/" + UUID.randomUUID(), "image/jpeg");
        file.storageSize(12L); file = files.save(file);
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
        uploadId = urls.issueUploadUrls(tripId, userId, request(1, 1, true)).uploadId();
    }

    private com.yeodam.yeodambe.trip.service.response.AdditionalAttachmentUploadBatchSnapshot prepareAsync() {
        jdbc.update("UPDATE additional_attachment_upload_batches SET next_attempt_at = '2099-01-01' WHERE addition_id <> ?", additionId);
        jdbc.update("UPDATE additional_attachment_upload_outbox SET next_attempt_at = '2099-01-01'");
        var response = completion.complete(tripId, userId, completeRequest());
        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(response.totalAttachments()).isEqualTo(1);
        assertThat(trips.findById(tripId).orElseThrow().getProcessingStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        var claim = transactions.claimBatch().orElseThrow();
        assertThat(claim.executionId()).isEqualTo(additionId);
        var item = claim.photos().getFirst();
        var keys = transactions.startPhotoAttempt(claim, item.itemId());
        transactions.savePhotoSuccess(claim, item.itemId(), new DerivedPhotoKeys(item.objectKey(), keys.get(0), keys.get(1), null,
                OffsetDateTime.parse("2026-10-07T12:00:00+09:00"), BigDecimal.ONE, BigDecimal.TEN, "camera", 12L, 8L, 4L, null));
        transactions.completeBatch(claim);
        assertThat(outboxCount()).isEqualTo(1);
        assertThat(attachments.countVisibleForEditByTripId(tripId)).isEqualTo(1);
        assertThat(attachments.findForEditWithCursor(tripId, null, null,
                org.springframework.data.domain.PageRequest.of(0, 19))).extracting(TripAttachment::getId).containsExactly(oldPhotoId);
        return claim;
    }

    private void publishBoth() {
        var ready = outboxes.findAll().stream().filter(value -> "photos_ready".equals(value.getMessageType())
                && value.getTripId().equals(tripId)).findFirst().orElseThrow();
        assertThat(executionRepository.findById(additionId).orElseThrow().getProcessOutboxId()).isNull();
        confirm(ready);
        var process = outboxes.findById(executionRepository.findById(additionId).orElseThrow().getProcessOutboxId()).orElseThrow();
        confirm(process);
    }

    private String successfulPayload() {
        var prepared = preparation.prepare(tripId, userId, uploadId);
        var body = (tools.jackson.databind.node.ObjectNode) result(prepared, false);
        body.putArray("failed");
        var place = (tools.jackson.databind.node.ObjectNode) body.path("places").get(0);
        place.put("first_taken_at", "2026-10-07T12:00:00+09:00");
        place.put("last_taken_at", "2026-10-07T12:00:00+09:00");
        return json.writeValueAsString(Map.of("type", "process_result", "trip_id", tripId,
                "execution_id", additionId, "status", "COMPLETED", "result", body));
    }

    private void confirm(AdditionalAttachmentUploadOutbox outbox) {
        outboxService.recordPublished(outbox.getId(), new OutboxReceipt(
                outbox.getTopic(), outbox.getTripId().toString(), outbox.getPayload(), Instant.now()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 결과_수신은_표시_상태가_바뀔_때만_FINALIZING을_발행한다(boolean alreadyFinalizing) {
        prepareAsync();
        publishBoth();
        String raw = successfulPayload();
        clearInvocations(notificationPublisher);
        if (alreadyFinalizing) {
            transaction.executeWithoutResult(status -> executionRepository.findForUpdate(additionId).orElseThrow()
                    .updateDisplayStatus(TripDisplayStatus.FINALIZING, null));
        }
        var event = applicationMapper.readValue(raw, com.yeodam.yeodambe.trip.event.TripCreateResultEvent.class);
        transaction.executeWithoutResult(status -> {
            assertThat(kafkaResults.receive(event, raw)).isTrue();
            verifyNoInteractions(notificationPublisher);
        });
        kafkaResults.receive(event, raw);

        if (alreadyFinalizing) {
            verifyNoInteractions(notificationPublisher);
        } else {
            var captor = ArgumentCaptor.forClass(WebSocketEvent.class);
            verify(notificationPublisher).publish(captor.capture());
            verifyNoMoreInteractions(notificationPublisher);
            var message = captor.getValue();
            var notification = (TripProcessingStatusNotification) message.payload().data();
            assertThat(message.target().id()).isEqualTo(userId);
            assertThat(notification.executionId()).isEqualTo(additionId);
            assertThat(notification.status()).isEqualTo(TripProcessingStatusResponse.Status.FINALIZING);
            assertThat(notification.statusVersion()).isEqualTo(executionRepository.findById(additionId).orElseThrow().getStatusVersion());
            assertThat(notification.result()).isNull();
            assertThat(notification.error()).isNull();
        }
        assertThat(attachments.findById(oldPhotoId).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    void 결과_수신을_롤백하면_FINALIZING을_발행하지_않는다() {
        prepareAsync();
        publishBoth();
        String raw = successfulPayload();
        clearInvocations(notificationPublisher);
        transaction.executeWithoutResult(status -> {
            kafkaResults.receive(applicationMapper.readValue(raw,
                    com.yeodam.yeodambe.trip.event.TripCreateResultEvent.class), raw);
            status.setRollbackOnly();
        });

        verifyNoInteractions(notificationPublisher);
        var execution = executionRepository.findById(additionId).orElseThrow();
        assertThat(execution.getResultRawPayload()).isNull();
        assertThat(execution.getDisplayStatus()).isEqualTo(TripDisplayStatus.PROCESSING);
        assertThat(execution.getStatusVersion()).isEqualTo(1);
    }

    @Test
    void 추가_완료를_롤백하면_이벤트를_발행하거나_기존_분류를_바꾸지_않는다() {
        prepareAsync();
        publishBoth();
        String raw = successfulPayload();
        kafkaResults.receive(applicationMapper.readValue(raw, com.yeodam.yeodambe.trip.event.TripCreateResultEvent.class), raw);
        jdbc.update("UPDATE additional_attachment_executions SET result_next_attempt_at = '2099-01-01' WHERE execution_id <> ?", additionId);
        var claim = kafkaResults.claim().orElseThrow();
        assertThat(kafkaResults.start(claim)).isTrue();
        var validated = kafkaResults.validate(claim);
        clearInvocations(notificationPublisher);
        transaction.executeWithoutResult(status -> {
            kafkaResults.complete(claim, validated, Map.of("place-one", "new place"));
            verifyNoInteractions(notificationPublisher);
            status.setRollbackOnly();
        });
        verifyNoInteractions(notificationPublisher);
        assertThat(executionRepository.findById(additionId).orElseThrow().getDisplayStatus()).isEqualTo(TripDisplayStatus.FINALIZING);
        assertThat(places.findById(oldPlaceId).orElseThrow().getDeletedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT change_sequence FROM trips WHERE trip_id = ?", Long.class, tripId)).isZero();
    }

    @Test
    void 비동기_파이프라인은_순서대로_발행하고_스토리를_유지하며_모든_사진을_한_번_적용한다() {
        Long storyId = createExistingStory();
        prepareAsync();
        assertThat(processingStatus.findStatus(tripId, userId).status())
                .isEqualTo(com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse.Status.PROCESSING);
        assertThat(places.findById(oldPlaceId).orElseThrow().getDeletedAt()).isNull();
        publishBoth();
        String raw = successfulPayload();
        var event = applicationMapper.readValue(raw, com.yeodam.yeodambe.trip.event.TripCreateResultEvent.class);
        assertThat(kafkaResults.receive(event, raw)).isTrue();
        assertThat(kafkaResults.receive(event, raw)).isTrue();
        jdbc.update("UPDATE additional_attachment_executions SET result_next_attempt_at = '2099-01-01' WHERE execution_id <> ?", additionId);
        var claim = kafkaResults.claim().orElseThrow();
        assertThat(kafkaResults.claim()).isEmpty();
        assertThat(kafkaResults.start(claim)).isTrue();
        var validated = kafkaResults.validate(claim);
        clearInvocations(notificationPublisher);
        kafkaResults.complete(claim, validated, Map.of("place-one", "new place"));
        var captor = ArgumentCaptor.forClass(WebSocketEvent.class);
        verify(notificationPublisher).publish(captor.capture());
        verifyNoMoreInteractions(notificationPublisher);
        var data = applicationMapper.valueToTree(captor.getValue().payload()).path("data");
        assertThat(data.path("eventType").asString()).isEqualTo("TRIP_ANALYSIS_APPLIED");
        assertThat(data.path("result").path("classifiedAttachmentCount").asInt()).isEqualTo(2);
        assertThat(data.path("listItem").path("sequence").asLong()).isEqualTo(1);
        assertThat(data.path("listItem").path("attachmentCount").asLong()).isEqualTo(2);
        kafkaResults.receive(event, raw);
        assertThat(executionRepository.findById(additionId).orElseThrow().getStatus()).isEqualTo(AdditionalAttachmentExecution.Status.COMPLETED);
        assertThat(attachments.countVisibleForEditByTripId(tripId)).isEqualTo(2);
        assertThat(batchStatus()).isEqualTo("COMPLETED");
        assertThat(places.findById(oldPlaceId).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(places.countByTripIdAndDeletedAtIsNull(tripId)).isEqualTo(1);
        assertThat(trips.findById(tripId).orElseThrow().getCurrentStoryId()).isEqualTo(storyId);
        assertThat(stats.findByUser_UserId(userId).orElseThrow().getStorageReservedBytes()).isZero();
        assertThat(processingStatus.findStatus(tripId, userId).status())
                .isEqualTo(com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse.Status.COMPLETED);
    }

    @Test
    void 중복_발행_수신은_최초_시각을_유지한다() {
        prepareAsync();
        var ready = outboxes.findAll().stream().filter(value -> value.getTripId().equals(tripId)
                && "photos_ready".equals(value.getMessageType())).findFirst().orElseThrow();
        confirm(ready);
        var time = outboxes.findById(ready.getId()).orElseThrow().getPublishedAt();
        confirm(ready);
        assertThat(outboxes.findById(ready.getId()).orElseThrow().getPublishedAt()).isEqualTo(time);
    }

    @Test
    void AI_결과가_실패하면_새_사진만_정리하고_기존_분류와_스토리를_유지한다() {
        Long storyId = createExistingStory();
        prepareAsync();
        publishBoth();
        String raw = json.writeValueAsString(Map.of("type", "process_result", "trip_id", tripId,
                "execution_id", additionId, "status", "FAILED", "error", Map.of("code", "AI_FAILED")));
        var event = applicationMapper.readValue(raw, com.yeodam.yeodambe.trip.event.TripCreateResultEvent.class);
        kafkaResults.receive(event, raw);
        assertThat(executionRepository.findById(additionId).orElseThrow().getStatus()).isEqualTo(AdditionalAttachmentExecution.Status.FAILED);
        assertThat(attachments.findById(oldPhotoId).orElseThrow().getDeletedAt()).isNull();
        assertThat(places.findById(oldPlaceId).orElseThrow().getDeletedAt()).isNull();
        assertThat(executionRepository.findById(additionId).orElseThrow().isTerminal()).isTrue();
        assertThat(attachments.countForEditByTripId(tripId)).isEqualTo(1);
        assertThat(trips.findById(tripId).orElseThrow().getCurrentStoryId()).isEqualTo(storyId);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void 회원_탈퇴는_대기_중인_추가를_중단하고_미변환_예약량을_해제한다() {
        completion.complete(tripId, userId, completeRequest());
        tripWithdrawal.withdrawAll(userId, LocalDateTime.now());
        var ended = executionRepository.findById(additionId).orElseThrow();
        assertThat(ended.isTerminal()).isTrue();
        assertThat(ended.getErrorCode()).isEqualTo("USER_WITHDRAWN");
        assertThat(stats.findByUser_UserId(userId).orElseThrow().getStorageReservedBytes()).isZero();
        assertThat(trips.findById(tripId).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT cleanup_started_at FROM additional_attachment_upload_items WHERE upload_batch_id = "
                + "(SELECT upload_batch_id FROM additional_attachment_upload_batches WHERE upload_id = ?)", LocalDateTime.class, uploadId)).isNotNull();
        verify(storage, never()).delete(anyString());
    }

    @Test
    void 추가_실패_객체는_30일_보존한_뒤_기존_사진을_삭제하지_않고_정리한다() {
        prepareAsync();
        transactions.failExecution(additionId, "IMAGE_CONVERSION_FAILED");
        var item = additionalItems.findAdditionItems(additionId).getFirst();
        var objects = transactions.cleanupObjects(item);
        objectCleanup.retryPending();
        for (var object : objects) verify(storage, never()).delete(object.key());
        var expired = LocalDateTime.now().minusDays(31);
        var aged = objects.stream().map(object -> new AdditionalAttachmentUploadTransactionService.CleanupObject(
                object.key(), expired, object.reason(), false)).toList();
        jdbc.update("UPDATE additional_attachment_upload_items SET cleanup_json = ?, cleanup_started_at = ? WHERE upload_item_id = ?",
                applicationMapper.writeValueAsString(aged), expired, item.getId());
        jdbc.update("UPDATE trip_attachments SET deleted_at = ? WHERE trip_attachment_id = ?", expired, item.getTripAttachmentId());
        objectCleanup.retryPending();
        for (var object : objects) verify(storage, atLeastOnce()).delete(object.key());
        verify(storage, never()).delete(files.findById(attachments.findById(oldPhotoId).orElseThrow().getFileId()).orElseThrow().getObjectKey());
        assertThat(additionalItems.findById(item.getId()).orElseThrow().getCleanupStartedAt()).isNull();
        assertThat(attachments.findById(item.getTripAttachmentId()).orElseThrow().getClassificationStatus()).isEqualTo(ClassificationStatus.DELETED);
        clearInvocations(storage);
        objectCleanup.retryPending();
        for (var object : objects) verify(storage, never()).delete(object.key());
    }

    @Test
    void process_발행_한_시간_후_시간_초과로_종료하고_늦은_결과를_무시한다() {
        prepareAsync();
        publishBoth();
        jdbc.update("UPDATE additional_attachment_executions SET process_published_at = CURRENT_TIMESTAMP - INTERVAL 61 MINUTE WHERE execution_id = ?", additionId);
        kafkaResults.expire();
        var ended = executionRepository.findById(additionId).orElseThrow();
        assertThat(ended.getErrorCode()).isEqualTo("AI_RESULT_TIMEOUT");
        String raw = json.writeValueAsString(Map.of("type", "process_result", "trip_id", tripId,
                "execution_id", additionId, "status", "COMPLETED", "result", Map.of()));
        kafkaResults.receive(applicationMapper.readValue(raw, com.yeodam.yeodambe.trip.event.TripCreateResultEvent.class), raw);
        assertThat(executionRepository.findById(additionId).orElseThrow().getResultRawPayload()).isNull();
        assertThat(attachments.findById(oldPhotoId).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    void photos_ready를_재사용하고_수신이_확인된_뒤에만_process를_생성한다() {
        var prepared = completion.prepareQueueBatch(tripId, userId, completeRequest());
        var saved = outboxService.save(tripId, userId, uploadId, prepared, "request-first");
        var ready = outboxes.findById(saved.photosReady().getId()).orElseThrow();
        assertThat(saved.process()).isEmpty();
        assertThat(outboxCount()).isEqualTo(1);
        assertThat(json.readTree(ready.getPayload())).isEqualTo(json.readTree(applicationMapper.writeValueAsString(prepared.photosReady())));
        confirm(ready);
        var process = outboxes.findById(executionRepository.findById(additionId).orElseThrow().getProcessOutboxId()).orElseThrow();
        assertThat(json.readTree(process.getPayload())).isEqualTo(json.readTree(applicationMapper.writeValueAsString(prepared.process().orElseThrow())));
        var repeated = outboxService.save(tripId, userId, uploadId, prepared, "request-repeated");
        assertThat(repeated.photosReady().getId()).isEqualTo(ready.getId());
        assertThat(repeated.photosReady().getStatus()).isEqualTo(AdditionalAttachmentUploadOutbox.Status.PUBLISHED);
        assertThat(repeated.photosReady().getRequestId()).isEqualTo("request-first");
        assertThat(repeated.photosReady().getPayload()).isEqualTo(ready.getPayload());
        assertThat(outboxCount()).isEqualTo(2);
    }

    @Test
    void 중간_배치는_photos_ready만_저장한다() {
        jdbc.update("UPDATE additional_attachment_upload_batches SET is_last_batch = false, "
                + "total_attachment_count = 2 WHERE upload_id = ?", uploadId);
        var prepared = completion.prepareQueueBatch(tripId, userId, completeRequest());
        assertThat(prepared.process()).isEmpty();
        var saved = outboxService.save(tripId, userId, uploadId, prepared, "request-intermediate");
        assertThat(saved.process()).isEmpty();
        assertThat(saved.photosReady().getMessageType()).isEqualTo("photos_ready");
        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    void 아웃박스_삽입이_실패하면_준비된_사진을_제거하지_않고_롤백한다() {
        var prepared = completion.prepareQueueBatch(tripId, userId, completeRequest());
        assertThatThrownBy(() -> outboxService.save(tripId, userId, uploadId, prepared, null))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(outboxCount()).isZero();
        assertThat(batchStatus()).isEqualTo("PREPARED");
        assertThat(attachments.findById(oldPhotoId)).isPresent();
        assertThat(prepared.process().orElseThrow().attachments()).allSatisfy(photo ->
                assertThat(attachments.findById(photo.tripAttachmentId())).isPresent());
    }

    @Test
    void 다른_소유자의_아웃박스_저장을_거부하고_메시지를_쓰지_않는다() {
        var prepared = completion.prepareQueueBatch(tripId, userId, completeRequest());
        Long otherUserId = users.save(new User(UUID.randomUUID() + "@yeodam.test", "다른회원")).getUserId();
        assertThatThrownBy(() -> outboxService.save(tripId, otherUserId, uploadId, prepared, "request-other"))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThat(outboxCount()).isZero();
    }

    @Test
    void 후속_배치_검증이_실패하면_추가를_종료하고_기존_여행_데이터를_보존한다() {
        transactions.failExecution(additionId, "TEST_RESET");
        additionId = UUID.randomUUID().toString();
        uploadId = urls.issueUploadUrls(tripId, userId, request(1, 2, false)).uploadId();
        var firstClaim = prepareAsync();
        var ready = outboxes.findAll().stream().filter(value -> value.getTripId().equals(tripId)
                && "photos_ready".equals(value.getMessageType())).findFirst().orElseThrow();
        confirm(ready);
        var second = urls.issueUploadUrls(tripId, userId, request(2, 2, true));
        when(storage.open(anyString())).thenAnswer(call -> new ByteArrayInputStream("broken photo".getBytes()));

        assertThatThrownBy(() -> completion.complete(tripId, userId,
                new AdditionalAttachmentUploadCompleteRequest(second.uploadId())))
                .isInstanceOf(com.yeodam.yeodambe.common.exception.UnsupportedAttachmentFormatException.class);

        var failed = executionRepository.findById(additionId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(AdditionalAttachmentExecution.Status.FAILED);
        assertThat(failed.getEndedAt()).isNotNull();
        assertThat(jdbc.queryForList("SELECT status FROM additional_attachment_upload_batches WHERE addition_id = ?",
                String.class, additionId)).hasSize(2).containsOnly("FAILED");
        assertThat(attachments.findById(oldPhotoId).orElseThrow().getDeletedAt()).isNull();
        assertThat(places.findById(oldPlaceId).orElseThrow().getDeletedAt()).isNull();
        assertThat(trips.findById(tripId).orElseThrow().getProcessingStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(stats.findByUser_UserId(userId).orElseThrow().getStorageReservedBytes()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM additional_attachment_upload_outbox WHERE trip_id = ? AND message_type = 'process'",
                Integer.class, tripId)).isZero();
        var firstItem = additionalItems.findAllByBatch_IdOrderByFileOrderAsc(firstClaim.batchId()).getFirst();
        assertThat(attachments.findById(firstItem.getTripAttachmentId()).orElseThrow().getDeletedAt()).isNotNull();
        verify(storage, never()).delete(anyString());
    }

    private Integer outboxCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM additional_attachment_upload_outbox o "
                + "JOIN additional_attachment_upload_batches b ON b.upload_batch_id = o.upload_batch_id "
                + "WHERE b.addition_id = ?", Integer.class, additionId);
    }

    @Test
    void 재분류는_재생성_없이_기존_스토리_행과_현재_스토리를_유지한다() {
        Long storyId = createExistingStory();
        LocalDate sourceDate = places.findById(oldPlaceId).orElseThrow().getStartedAt().toLocalDate();
        var beforeStory = jdbc.queryForMap("SELECT * FROM stories WHERE story_id = ?", storyId);
        var beforeBlocks = jdbc.queryForList("SELECT * FROM story_blocks WHERE story_id = ?", storyId);
        var beforeSelections = jdbc.queryForList("SELECT * FROM story_selected_places WHERE story_id = ?", storyId);
        completion.prepareBatchPhotos(tripId, userId, completeRequest());
        var prepared = preparation.prepare(tripId, userId, uploadId);
        results.acceptResult(tripId, userId, additionId, result(prepared, false));

        assertThat(jdbc.queryForMap("SELECT * FROM stories WHERE story_id = ?", storyId)).isEqualTo(beforeStory);
        assertThat(jdbc.queryForList("SELECT * FROM story_blocks WHERE story_id = ?", storyId)).isEqualTo(beforeBlocks);
        assertThat(jdbc.queryForList("SELECT * FROM story_selected_places WHERE story_id = ?", storyId)).isEqualTo(beforeSelections);
        assertThat(jdbc.queryForObject("SELECT current_story_id FROM trips WHERE trip_id = ?", Long.class, tripId))
                .isEqualTo(storyId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stories WHERE trip_id = ?", Integer.class, tripId)).isEqualTo(1);
        when(storage.createReadUrl(anyString())).thenReturn("https://example.test/story-photo");
        jdbc.update("UPDATE trip_detail_places SET started_at = '2099-01-01 00:00:00' WHERE trip_place_id = ?", oldPlaceId);
        var detail = storyReadService.findStory(userId, tripId);
        assertThat(detail.storyId()).isEqualTo(storyId);
        assertThat(detail.storySummary()).isEqualTo("기존 스토리");
        assertThat(detail.days()).hasSize(1);
        assertThat(detail.days().getFirst().date()).isEqualTo(sourceDate);
        assertThat(detail.days().getFirst().blocks().getFirst().tripPlaceId()).isEqualTo(oldPlaceId);
        assertThat(attachments.findById(oldPhotoId).orElseThrow().getTripPlaceId()).isNotEqualTo(oldPlaceId);
        assertThat(detail.days().getFirst().blocks().getFirst().tripAttachmentId()).isEqualTo(oldPhotoId);
    }

    @Test
    void 추가가_실패해도_기존_스토리와_조회_가능한_사진을_유지한다() {
        Long storyId = createExistingStory();
        var beforeStory = jdbc.queryForMap("SELECT * FROM stories WHERE story_id = ?", storyId);
        var beforeBlocks = jdbc.queryForList("SELECT * FROM story_blocks WHERE story_id = ?", storyId);
        completion.prepareBatchPhotos(tripId, userId, completeRequest());
        preparation.prepare(tripId, userId, uploadId);
        results.fail(tripId, userId, additionId);

        assertThat(batchStatus()).isEqualTo("FAILED");
        assertThat(jdbc.queryForMap("SELECT * FROM stories WHERE story_id = ?", storyId)).isEqualTo(beforeStory);
        assertThat(jdbc.queryForList("SELECT * FROM story_blocks WHERE story_id = ?", storyId)).isEqualTo(beforeBlocks);
        assertThat(attachments.findById(oldPhotoId).orElseThrow().getTripPlaceId()).isEqualTo(oldPlaceId);
        assertThat(places.findById(oldPlaceId).orElseThrow().getDeletedAt()).isNull();
        when(storage.createReadUrl(anyString())).thenReturn("https://example.test/story-photo");
        assertThat(storyReadService.findStory(userId, tripId).storyId()).isEqualTo(storyId);
    }

    @Test
    void 사진이_미분류가_되어도_원본_장소와_날짜로_스토리를_조회한다() {
        Long storyId = createExistingStory();
        LocalDate sourceDate = places.findById(oldPlaceId).orElseThrow().getStartedAt().toLocalDate();
        completion.prepareBatchPhotos(tripId, userId, completeRequest());
        var prepared = preparation.prepare(tripId, userId, uploadId);
        var unclassified = prepared.request().attachments().stream()
                .map(photo -> Map.<String, Object>of("trip_attachment_id", photo.tripAttachmentId(),
                        "issue", "BLURRY", "region_origin", "EXIF", "evaluation", 10)).toList();
        JsonNode outcome = json.valueToTree(Map.of("trip_id", tripId, "execution_id", additionId,
                "places", List.of(), "unclassified", unclassified));
        results.acceptResult(tripId, userId, additionId, outcome);

        assertThat(batchStatus()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT current_story_id FROM trips WHERE trip_id = ?", Long.class, tripId))
                .isEqualTo(storyId);
        assertThat(attachments.findById(oldPhotoId).orElseThrow().getTripPlaceId()).isNull();
        when(storage.createReadUrl(anyString())).thenReturn("https://example.test/story-photo");
        var detail = storyReadService.findStory(userId, tripId);
        assertThat(detail.storyId()).isEqualTo(storyId);
        assertThat(detail.storySummary()).isEqualTo("기존 스토리");
        assertThat(detail.days().getFirst().date()).isEqualTo(sourceDate);
        var block = detail.days().getFirst().blocks().getFirst();
        assertThat(block.tripPlaceId()).isEqualTo(oldPlaceId);
        assertThat(block.tripAttachmentId()).isEqualTo(oldPhotoId);
        assertThat(block.detailSummary()).isEqualTo("기존 사진 이야기");
        assertThat(block.memo()).isEqualTo("기존 메모");
    }

    @Test
    void 스토리의_원본_장소와_날짜는_필수이며_없는_원본_장소를_거부한다() {
        Long storyId = createExistingStory();
        var snapshot = jdbc.queryForMap("SELECT source_trip_place_id, source_date FROM story_blocks WHERE story_id = ?", storyId);
        assertThat(((Number) snapshot.get("source_trip_place_id")).longValue()).isEqualTo(oldPlaceId);
        assertThat(snapshot.get("source_date")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'story_blocks' "
                + "AND column_name IN ('source_trip_place_id', 'source_date') AND is_nullable = 'NO'", Integer.class))
                .isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("UPDATE story_blocks SET source_date = NULL WHERE story_id = ?", storyId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE story_blocks SET source_trip_place_id = ? WHERE story_id = ?",
                Long.MAX_VALUE, storyId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private Long createExistingStory() {
        jdbc.update("INSERT INTO stories (trip_id, mood, story_summary, processing_status) "
                + "VALUES (?, 'PLAIN', '기존 스토리', 'COMPLETED')", tripId);
        Long storyId = jdbc.queryForObject("SELECT story_id FROM stories WHERE trip_id = ?", Long.class, tripId);
        jdbc.update("INSERT INTO story_selected_places (story_id, trip_place_id) VALUES (?, ?)", storyId, oldPlaceId);
        jdbc.update("INSERT INTO story_blocks (story_id, trip_attachment_id, source_trip_place_id, source_date, "
                + "order_number, day_label, detail_summary, memo) "
                + "VALUES (?, ?, ?, ?, 1, '첫째 날', '기존 사진 이야기', '기존 메모')", storyId, oldPhotoId,
                oldPlaceId, places.findById(oldPlaceId).orElseThrow().getStartedAt().toLocalDate());
        jdbc.update("UPDATE trips SET current_story_id = ? WHERE trip_id = ?", storyId, tripId);
        Long blockId = jdbc.queryForObject("SELECT story_block_id FROM story_blocks WHERE story_id = ?",
                Long.class, storyId);
        storyContents.insert(new com.yeodam.yeodambe.story.document.StoryContentDocument(
                storyId, com.yeodam.yeodambe.story.entity.Story.Mood.PLAIN, "기존 스토리",
                List.of(oldPlaceId), List.of(new com.yeodam.yeodambe.story.document.StoryContentBlock(
                blockId, oldPhotoId, oldPlaceId,
                places.findById(oldPlaceId).orElseThrow().getStartedAt().toLocalDate(),
                "첫째 날", "기존 사진 이야기", "기존 메모", null, null, null)), blockId + 1));
        return storyId;
    }

    @Test
    void 모든_사진을_준비하고_여행_ID를_유지하며_분류를_한_번_교체한다() {
        var photos = completion.prepareBatchPhotos(tripId, userId, completeRequest());
        completion.prepareBatchPhotos(tripId, userId, completeRequest());
        verify(derivatives, times(1)).createAll(eq(uploadId), anyList(), anyList());
        verify(storage, times(2)).retain(anyList());
        var prepared = preparation.prepare(tripId, userId, uploadId);
        assertThat(prepared.request().attachments()).extracting(photo -> photo.tripAttachmentId()).contains(oldPhotoId);
        assertThat(prepared.request().attachments()).hasSize(2);
        assertThat(prepared.request().attachments()).extracting(photo -> photo.analyzeStorageKey())
                .containsExactly("old-analyze", photos.get(0).analyzeKey());
        assertThat(prepared.request().executionId()).isEqualTo(additionId);
        assertThat(prepared.request().regions()).hasSize(1);
        assertThat(batchStatus()).isEqualTo("PREPARED");
        var result = result(prepared, false);
        results.acceptResult(tripId, userId, additionId, result);
        assertThat(batchStatus()).isEqualTo("COMPLETED");
        assertThat(places.countByTripIdAndDeletedAtIsNull(tripId)).isEqualTo(1);
        assertThat(places.findById(oldPlaceId).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(attachments.findById(oldPhotoId).orElseThrow().getTripPlaceId()).isNotEqualTo(oldPlaceId);
        assertThat(trips.findById(tripId).orElseThrow().getProcessingStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(attachments.countForEditByTripId(tripId)).isEqualTo(2);
        assertThat(stats.findByUser_UserId(userId).orElseThrow().getAttachmentCount()).isEqualTo(2);
        clearInvocations(kakao);
        results.acceptResult(tripId, userId, additionId, result);
        verifyNoInteractions(kakao);
        assertThat(places.countByTripIdAndDeletedAtIsNull(tripId)).isEqualTo(1);
    }

    @Test
    void 잘못된_결과는_기존_분류를_유지하고_올바른_결과로_재시도할_수_있다() {
        completion.prepareBatchPhotos(tripId, userId, completeRequest());
        var prepared = preparation.prepare(tripId, userId, uploadId);
        assertThatThrownBy(() -> resultTransactions.saveResult(tripId, userId, additionId,
                result(prepared, true), Map.of("place-one", "새 장소")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(batchStatus()).isEqualTo("PREPARED");
        assertThat(places.findById(oldPlaceId).orElseThrow().getDeletedAt()).isNull();
        assertThat(attachments.findById(oldPhotoId).orElseThrow().getTripPlaceId()).isEqualTo(oldPlaceId);
        results.acceptResult(tripId, userId, additionId, result(prepared, false));
        assertThat(batchStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void 기존_분류_사진을_미분류로_옮기고_이전_장소_연결을_제거한다() {
        completion.prepareBatchPhotos(tripId, userId, completeRequest());
        var prepared = preparation.prepare(tripId, userId, uploadId);
        List<Map<String, Object>> unclassified = prepared.request().attachments().stream()
                .map(photo -> Map.<String, Object>of("trip_attachment_id", photo.tripAttachmentId(),
                        "issue", "BLURRY", "region_origin", "EXIF", "evaluation", 10)).toList();
        JsonNode result = json.valueToTree(Map.of("trip_id", tripId, "execution_id", additionId,
                "places", List.of(), "unclassified", unclassified));
        results.acceptResult(tripId, userId, additionId, result);
        TripAttachment old = attachments.findById(oldPhotoId).orElseThrow();
        assertThat(old.getTripPlaceId()).isNull();
        assertThat(old.getIssue()).isEqualTo(AttachmentIssue.BLURRY);
        assertThat(places.countByTripIdAndDeletedAtIsNull(tripId)).isZero();
        assertThat(trips.findById(tripId).orElseThrow().getThumbnailKey()).isNull();
    }

    @Test
    void 작업이_실패해도_기존_데이터를_삭제하지_않고_늦은_결과를_무시한다() {
        completion.prepareBatchPhotos(tripId, userId, completeRequest());
        var prepared = preparation.prepare(tripId, userId, uploadId);
        results.fail(tripId, userId, additionId);
        assertThat(batchStatus()).isEqualTo("FAILED");
        assertThat(places.findById(oldPlaceId).orElseThrow().getDeletedAt()).isNull();
        results.acceptResult(tripId, userId, additionId, result(prepared, false));
        verifyNoInteractions(kakao);
        assertThat(places.countByTripIdAndDeletedAtIsNull(tripId)).isEqualTo(1);
        assertThat(attachments.countForEditByTripId(tripId)).isEqualTo(2);
    }

    @Test
    void 두_번째_변환_선점을_거부하고_일치하는_토큰만_해제한다() {
        completion.verifyBatch(tripId, userId, completeRequest());
        var claim = transactions.claimConversion(tripId, userId, uploadId);
        assertThat(batchStatus()).isEqualTo("CONVERTING");
        assertThatThrownBy(() -> transactions.claimConversion(tripId, userId, uploadId))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        assertThatThrownBy(() -> transactions.releaseConversion(tripId, userId, uploadId, "wrong-token"))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        transactions.releaseConversion(tripId, userId, uploadId, claim.token());
        assertThat(batchStatus()).isEqualTo("VERIFIED");
    }

    @Test
    void 추가_중에는_사진과_여행_변경을_막고_조회는_허용한다() {
        assertThatThrownBy(() -> deletion.deleteOne(userId, oldPhotoId))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        assertThatThrownBy(() -> tripService.updateTrip(tripId, userId, new TripUpdateRequest("수정 이름", null, null, null)))
                .isInstanceOf(TripUpdateNotAllowedException.class);
        assertThatThrownBy(() -> tripDeletion.delete(tripId, userId))
                .isInstanceOf(TripDeletionNotAllowedException.class);
        assertThat(attachments.findAccessibleById(oldPhotoId, userId)).isPresent();
        assertThat(places.countByTripIdAndDeletedAtIsNull(tripId)).isEqualTo(1);
    }

    @Test
    void 보존_처리가_실패하면_재변환_없이_저장된_사진을_재사용한다() {
        doThrow(new IllegalStateException("test retain failure")).doNothing().when(storage).retain(anyList());
        assertThatThrownBy(() -> completion.prepareBatchPhotos(tripId, userId, completeRequest()))
                .isInstanceOf(IllegalStateException.class).hasMessage("test retain failure");
        assertThat(attachments.countForEditByTripId(tripId)).isEqualTo(2);
        completion.prepareBatchPhotos(tripId, userId, completeRequest());
        verify(derivatives, times(1)).createAll(eq(uploadId), anyList(), anyList());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void 변환이_실패하면_선점을_해제하고_원본과_기존_사진을_보존한다() {
        when(derivatives.createAll(anyString(), anyList(), anyList()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("test conversion")));
        assertThatThrownBy(() -> completion.prepareBatchPhotos(tripId, userId, completeRequest()))
                .isInstanceOf(CompletionException.class);
        assertThat(batchStatus()).isEqualTo("VERIFIED");
        assertThat(attachments.countForEditByTripId(tripId)).isEqualTo(1);
        assertThat(places.findById(oldPlaceId).orElseThrow().getDeletedAt()).isNull();
        verify(storage, never()).delete(anyString());
    }

    @Test
    void 모든_배치_이후에만_분석을_준비하고_반복_요청은_준비된_입력을_재사용한다() {
        jdbc.update("DELETE FROM additional_attachment_upload_items WHERE object_key LIKE ?", "trip-additions/" + additionId + "/original/%");
        jdbc.update("DELETE FROM additional_attachment_upload_batches WHERE addition_id = ?", additionId);
        jdbc.update("DELETE FROM additional_attachment_executions WHERE execution_id = ?", additionId);
        jdbc.update("UPDATE user_stats SET storage_reserved_bytes = 0 WHERE user_id = ?", userId);
        uploadId = urls.issueUploadUrls(tripId, userId, request(1, 2, false)).uploadId();
        assertThat(completion.prepareAddition(tripId, userId, completeRequest())).isEmpty();
        assertThat(batchStatus()).isEqualTo("VERIFIED");
        uploadId = urls.issueUploadUrls(tripId, userId, request(2, 2, true)).uploadId();
        var prepared = completion.prepareAddition(tripId, userId, completeRequest()).orElseThrow();
        assertThat(prepared.request().attachments()).hasSize(3);
        assertThat(jdbc.queryForList("SELECT status FROM additional_attachment_upload_batches WHERE addition_id = ? ORDER BY batch_no",
                String.class, additionId)).containsExactly("PREPARED", "PREPARED");
        clearInvocations(derivatives);
        var repeated = completion.prepareAddition(tripId, userId, completeRequest()).orElseThrow();
        assertThat(repeated.request()).isEqualTo(prepared.request());
        verifyNoInteractions(derivatives);
    }

    @Test
    void 동일한_결과를_동시에_적용해도_분류를_한_번만_교체한다() throws Exception {
        var prepared = completion.prepareAddition(tripId, userId, completeRequest()).orElseThrow();
        JsonNode result = result(prepared, false);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> calls = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                calls.add(executor.submit(() -> {
                    try { start.await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    resultTransactions.saveResult(tripId, userId, additionId, result, Map.of("place-one", "새 장소"));
                }));
            }
            start.countDown();
            for (Future<?> call : calls) call.get(15, TimeUnit.SECONDS);
        }
        assertThat(batchStatus()).isEqualTo("COMPLETED");
        assertThat(places.countByTripIdAndDeletedAtIsNull(tripId)).isEqualTo(1);
        assertThat(attachments.countForEditByTripId(tripId)).isEqualTo(2);
    }

    @Test
    void 다른_작업_식별자와_누락되거나_중복된_사진_결과를_거부한다() {
        var prepared = completion.prepareAddition(tripId, userId, completeRequest()).orElseThrow();
        JsonNode wrongJob = json.valueToTree(Map.of("trip_id", tripId, "execution_id", "other-job", "places", List.of(), "unclassified", List.of()));
        assertThatThrownBy(() -> results.acceptResult(tripId, userId, additionId, wrongJob)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(kakao);
        JsonNode missing = json.valueToTree(Map.of("trip_id", tripId, "execution_id", additionId, "places", List.of(), "unclassified", List.of()));
        assertThatThrownBy(() -> resultTransactions.saveResult(tripId, userId, additionId, missing, Map.of()))
                .isInstanceOf(IllegalStateException.class);
        var photo = Map.of("trip_attachment_id", oldPhotoId, "issue", "BLURRY", "region_origin", "EXIF");
        JsonNode duplicated = json.valueToTree(Map.of("trip_id", tripId, "execution_id", additionId,
                "places", List.of(), "unclassified", List.of(photo, photo)));
        assertThatThrownBy(() -> resultTransactions.saveResult(tripId, userId, additionId, duplicated, Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(places.findById(oldPlaceId).orElseThrow().getDeletedAt()).isNull();
        assertThat(batchStatus()).isEqualTo("PREPARED");
    }

    @Test
    void 큐_배치는_새_키만_포함하고_process는_동일한_시도_ID로_모든_사진을_포함한다() {
        var prepared = completion.prepareQueueBatch(tripId, userId, completeRequest());
        var ready = prepared.photosReady();
        var process = prepared.process().orElseThrow();
        assertThat(ready.type()).isEqualTo("photos_ready");
        assertThat(ready.tripId()).isEqualTo(tripId);
        assertThat(ready.batchNo()).isEqualTo(1);
        assertThat(ready.attachments()).hasSize(1);
        assertThat(ready.attachments()).extracting(photo -> photo.tripAttachmentId()).doesNotContain(oldPhotoId);
        assertThat(process.type()).isEqualTo("process");
        assertThat(process.attachments()).hasSize(2);
        assertThat(process.executionId()).isEqualTo(ready.executionId()).isEqualTo(additionId);
        var readyJson = json.valueToTree(ready).path("attachments").get(0);
        assertThat(readyJson.size()).isEqualTo(2);
        assertThat(readyJson.has("taken_at")).isFalse();
        assertThat(json.valueToTree(process).path("trip_id").asLong()).isEqualTo(tripId);
        clearInvocations(derivatives);
        var repeated = completion.prepareQueueBatch(tripId, userId, completeRequest());
        assertThat(repeated).isEqualTo(prepared);
        verifyNoInteractions(derivatives);
    }

    @Test
    void 초기와_추가_사진의_원본_시각을_보존하고_추론된_좌표를_제외한다() {
        var sourceBatch = initialBatches.saveAndFlush(new InitialAttachmentUploadBatch(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), tripId, userId, 1, 1, true));
        var sourceItem = new InitialAttachmentUploadItem(sourceBatch, 1, "old.jpg", "image/jpeg", 12L, "old-original");
        sourceItem.linkAttachment(oldPhotoId);
        sourceItem.recordTakenAt(OffsetDateTime.parse("2026-10-01T10:00:00+05:30"));
        initialItems.saveAndFlush(sourceItem);
        jdbc.update("UPDATE trip_attachments SET region_origin = 'INFERRED', taken_at = '2099-01-01 00:00:00', latitude = 50, longitude = 60 WHERE trip_attachment_id = ?",
                oldPhotoId);
        var message = completion.prepareQueueBatch(tripId, userId, completeRequest())
                .process().orElseThrow();
        var old = message.attachments().stream().filter(photo -> photo.tripAttachmentId().equals(oldPhotoId)).findFirst().orElseThrow();
        assertThat(old.takenAt()).isEqualTo(OffsetDateTime.parse("2026-10-01T10:00:00+05:30"));
        assertThat(old.latitude()).isNull();
        assertThat(old.longitude()).isNull();
        var added = message.attachments().stream().filter(photo -> !photo.tripAttachmentId().equals(oldPhotoId)).findFirst().orElseThrow();
        assertThat(added.takenAt()).isEqualTo(OffsetDateTime.parse("2026-10-07T12:00:00+09:00"));
        assertThat(added.latitude()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(added.longitude()).isEqualByComparingTo(BigDecimal.TEN);
    }

    @Test
    void 원본_시각이_없으면_AI_보정_시각을_쓰지_않고_null을_유지한다() {
        jdbc.update("UPDATE trip_attachments SET taken_at = '2099-01-01 00:00:00', region_origin = 'UNKNOWN' WHERE trip_attachment_id = ?", oldPhotoId);
        var message = completion.prepareQueueBatch(tripId, userId, completeRequest())
                .process().orElseThrow();
        var old = message.attachments().stream().filter(photo -> photo.tripAttachmentId().equals(oldPhotoId)).findFirst().orElseThrow();
        assertThat(old.takenAt()).isNull();
        assertThat(old.latitude()).isNull();
        assertThat(old.longitude()).isNull();
        assertThat(old.deviceModel()).isEqualTo("camera");
        var body = json.valueToTree(message);
        assertThat(body.path("attachments").get(0).has("taken_at")).isTrue();
        assertThat(body.path("attachments").get(0).path("taken_at").isNull()).isTrue();
    }

    private JsonNode result(AdditionalAttachmentAnalysisPreparationService.PreparedAnalysis prepared, boolean invalidScore) {
        List<Map<String, Object>> photos = prepared.request().attachments().stream().map(photo ->
                Map.<String, Object>of("trip_attachment_id", photo.tripAttachmentId(), "region_origin", "EXIF",
                        "taken_at", "2026-10-07T12:00:00+09:00", "latitude", 1, "longitude", 10,
                        "evaluation", invalidScore ? 101 : 90)).toList();
        return json.valueToTree(Map.of("trip_id", tripId, "execution_id", additionId,
                "places", List.of(Map.of("place_id", "place-one", "representative_attachment_id", oldPhotoId,
                        "latitude", 1, "longitude", 10, "attachments", photos)), "unclassified", List.of()));
    }

    private AdditionalAttachmentUploadUrlRequest request(int batchNo, int total, boolean complete) {
        return new AdditionalAttachmentUploadUrlRequest(additionId, batchNo, total, complete,
                List.of(new AdditionalAttachmentUploadUrlRequest.Attachment("new.jpg", "image/jpeg", 12L)));
    }
    private AdditionalAttachmentUploadCompleteRequest completeRequest() { return new AdditionalAttachmentUploadCompleteRequest(uploadId); }
    private String batchStatus() { return jdbc.queryForObject("SELECT status FROM additional_attachment_upload_batches WHERE upload_id = ?", String.class, uploadId); }
}
