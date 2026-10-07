package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
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
@Import(TestcontainersConfiguration.class)
class AdditionalAttachmentReanalysisIntegrationTest {
    @Autowired private AdditionalAttachmentUploadUrlService urls;
    @Autowired private AdditionalAttachmentUploadCompletionService completion;
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

    @Test
    void preparesAllPhotosAndReplacesClassificationOnceWithoutChangingTripId() {
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
    void keepsOldClassificationOnMalformedResultAndAllowsCorrectResultRetry() {
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
    void movesPreviouslyClassifiedPhotoToUnclassifiedAndClearsOldPlaceLink() {
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
    void failsJobWithoutDeletingOldDataAndIgnoresLateResult() {
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
    void rejectsSecondConversionClaimAndReleasesOnlyMatchingToken() {
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
    void blocksPhotoAndTripChangesWhileKeepingQueriesAvailable() {
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
    void retainFailureReusesSavedPhotosInsteadOfReconversion() {
        doThrow(new IllegalStateException("test retain failure")).doNothing().when(storage).retain(anyList());
        assertThatThrownBy(() -> completion.prepareBatchPhotos(tripId, userId, completeRequest()))
                .isInstanceOf(IllegalStateException.class).hasMessage("test retain failure");
        assertThat(attachments.countForEditByTripId(tripId)).isEqualTo(2);
        completion.prepareBatchPhotos(tripId, userId, completeRequest());
        verify(derivatives, times(1)).createAll(eq(uploadId), anyList(), anyList());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void conversionFailureReleasesClaimAndPreservesOriginalAndExistingPhoto() {
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
    void preparesAnalysisOnlyAfterAllBatchesAndReusesPreparedInputOnRepeat() {
        jdbc.update("DELETE FROM additional_attachment_upload_items WHERE object_key LIKE ?", "trip-additions/" + additionId + "/original/%");
        jdbc.update("DELETE FROM additional_attachment_upload_batches WHERE addition_id = ?", additionId);
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
    void concurrentIdenticalResultsCreateOnlyOneReplacement() throws Exception {
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
    void rejectsOtherJobIdentityAndMissingOrDuplicatePhotoResults() {
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
    void queueBatchHasOnlyNewKeysAndProcessHasAllPhotosWithSameAttemptId() {
        var prepared = completion.prepareQueueBatch(tripId, userId, completeRequest(), "queue-attempt");
        var ready = prepared.photosReady();
        var process = prepared.process().orElseThrow();
        assertThat(ready.type()).isEqualTo("photos_ready");
        assertThat(ready.tripId()).isEqualTo(tripId);
        assertThat(ready.batchNo()).isEqualTo(1);
        assertThat(ready.attachments()).hasSize(1);
        assertThat(ready.attachments()).extracting(photo -> photo.tripAttachmentId()).doesNotContain(oldPhotoId);
        assertThat(process.type()).isEqualTo("process");
        assertThat(process.attachments()).hasSize(2);
        assertThat(process.executionId()).isEqualTo(ready.executionId()).isEqualTo("queue-attempt");
        var readyJson = json.valueToTree(ready).path("attachments").get(0);
        assertThat(readyJson.size()).isEqualTo(2);
        assertThat(readyJson.has("taken_at")).isFalse();
        assertThat(json.valueToTree(process).path("trip_id").asLong()).isEqualTo(tripId);
        clearInvocations(derivatives);
        var repeated = completion.prepareQueueBatch(tripId, userId, completeRequest(), "queue-attempt");
        assertThat(repeated).isEqualTo(prepared);
        verifyNoInteractions(derivatives);
    }

    @Test
    void preservesRawInitialAndAdditionalTimesAndOmitsInferredCoordinates() {
        var sourceBatch = initialBatches.saveAndFlush(new InitialAttachmentUploadBatch(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), tripId, userId, 1, 1, true));
        var sourceItem = new InitialAttachmentUploadItem(sourceBatch, 1, "old.jpg", "image/jpeg", 12L, "old-original");
        sourceItem.linkAttachment(oldPhotoId);
        sourceItem.recordTakenAt(OffsetDateTime.parse("2026-10-01T10:00:00+05:30"));
        initialItems.saveAndFlush(sourceItem);
        jdbc.update("UPDATE trip_attachments SET region_origin = 'INFERRED', taken_at = '2099-01-01 00:00:00', latitude = 50, longitude = 60 WHERE trip_attachment_id = ?",
                oldPhotoId);
        var message = completion.prepareQueueBatch(tripId, userId, completeRequest(), "queue-attempt")
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
    void missingOriginalTimeStaysNullInsteadOfUsingAiCorrectedTime() {
        jdbc.update("UPDATE trip_attachments SET taken_at = '2099-01-01 00:00:00', region_origin = 'UNKNOWN' WHERE trip_attachment_id = ?", oldPhotoId);
        var message = completion.prepareQueueBatch(tripId, userId, completeRequest(), "queue-attempt")
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
