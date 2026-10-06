package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.*;
import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.integration.service.request.TripPhotoAnalysisRequest;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.request.*;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InitialAttachmentUploadFlowIntegrationTest {
    @Autowired private InitialAttachmentUploadUrlService urls;
    @Autowired private InitialAttachmentUploadCompletionService completion;
    @Autowired private InitialAttachmentUploadTransactionService transactions;
    @Autowired private TripProcessingCancellationService cancellation;
    @Autowired private TripProcessingStatusService statuses;
    @Autowired private TripAttachmentTransactionService legacy;
    @Autowired private TripAnalysisResultService results;
    @Autowired private InitialAttachmentUploadBatchRepository batches;
    @Autowired private InitialAttachmentUploadItemRepository items;
    @Autowired private TripRepository trips;
    @Autowired private TripRegionRepository regions;
    @Autowired private UserRepository users;
    @Autowired private UserStatsRepository stats;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private TripAttachmentStorageClient storage;
    @MockitoBean private TripAttachmentDerivativeService derivatives;
    @MockitoBean private TripPhotoAnalysisService analysis;
    @MockitoBean private TripPlaceNameService names;

    private User owner;
    private Trip trip;
    private static final OffsetDateTime TAKEN_AT = OffsetDateTime.parse("2026-10-01T10:00:00+09:00");
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        owner = users.save(new User(UUID.randomUUID() + "@yeodam.test", "업로드회원"));
        stats.save(new UserStats(owner));
        trip = trips.save(new Trip(owner.getUserId(), "직접 업로드", LocalDate.now(), LocalDate.now()));
        regions.save(new TripRegion(trip, "11000", "서울특별시", new BigDecimal("37.5665"), new BigDecimal("126.9780")));
        when(storage.size(anyString())).thenReturn(12L);
        when(storage.open(anyString())).thenAnswer(call -> new ByteArrayInputStream(
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 0, 0, 0, 0, 0, 0, 0, 0}));
        when(derivatives.createAll(anyString(), anyList(), anyList())).thenAnswer(call -> {
            List<String> keys = call.getArgument(1);
            return CompletableFuture.completedFuture(keys.stream().map(key -> new DerivedPhotoKeys(
                    key, key + "-analyze", key + "-preview", null, TAKEN_AT,
                    BigDecimal.ONE, BigDecimal.TEN, "camera", 12L, 8L, 4L, null)).toList());
        });
        when(analysis.analyze(anyLong(), anyString(), any(), any())).thenAnswer(call -> {
            assertThat(((BooleanSupplier) call.getArgument(3)).getAsBoolean()).isTrue();
            assertThat(latestStatus()).isEqualTo("ANALYZING");
            return result(call.getArgument(2));
        });
        when(names.resolve(anyLong(), anyString(), any(), any())).thenAnswer(call -> {
            assertThat(((BooleanSupplier) call.getArgument(3)).getAsBoolean()).isTrue();
            return Map.of();
        });
    }

    @Test
    void completesOrderedBatchesOnceAndPreservesOffsetAfterDatabaseReload() {
        var first = prepare(1, 2, false);
        assertThat(complete(first)).isEmpty();
        assertThat(latestStatus()).isEqualTo("COMPLETED");
        assertThat(statuses.findStatus(trip.getId(), owner.getUserId()).status())
                .isEqualTo(TripProcessingStatusResponse.Status.PROCESSING);
        verifyNoInteractions(analysis);
        assertThat(complete(first)).isEmpty();

        var last = prepare(2, 2, true);
        var response = complete(last).orElseThrow();
        assertThat(response.status()).isEqualTo(TripProcessingStatusResponse.Status.COMPLETED);
        assertThat(response.result().unclassifiedAttachmentCount()).isEqualTo(2);
        assertThat(latestStatus()).isEqualTo("COMPLETED");
        assertThat(trips.findById(trip.getId()).orElseThrow().getProcessingStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(complete(last).orElseThrow()).isEqualTo(response);
        var captor = org.mockito.ArgumentCaptor.forClass(TripPhotoAnalysisRequest.class);
        verify(analysis).analyze(eq(trip.getId()), eq(last.getExecutionId()), captor.capture(), any());
        var request = captor.getValue();
        assertThat(request.attachments()).hasSize(2).allSatisfy(photo -> assertThat(photo.takenAt()).isEqualTo(TAKEN_AT));
        var ordered = items.findExecutionItems(last.getExecutionId());
        assertThat(request.attachments()).extracting(TripPhotoAnalysisRequest.Photo::tripAttachmentId)
                .containsExactlyElementsOf(ordered.stream().map(InitialAttachmentUploadItem::getTripAttachmentId).toList());
        assertThat(request.regions()).hasSize(1);
        verify(derivatives, times(2)).createAll(anyString(), anyList(), anyList());
        assertThat(stats.findByUser_UserId(owner.getUserId()).orElseThrow().getAttachmentCount()).isEqualTo(2);
        assertThat(stats.findByUser_UserId(owner.getUserId()).orElseThrow().getStorageUsedBytes()).isEqualTo(48L);
    }

    @Test
    void retriesAnalysisFailureWithoutGeneratingOrPersistingPhotosAgain() {
        var batch = prepare(1, 1, true);
        doAnswer(call -> {
            ((BooleanSupplier) call.getArgument(3)).getAsBoolean();
            throw new AiProcessingFailedException(trip.getId(), 0, 1, "cluster", "AI_PROCESSING_FAILED", "분석 실패");
        }).doAnswer(call -> {
            assertThat(((BooleanSupplier) call.getArgument(3)).getAsBoolean()).isTrue();
            return result(call.getArgument(2));
        }).when(analysis).analyze(anyLong(), anyString(), any(), any());

        assertThat(complete(batch).orElseThrow().status()).isEqualTo(TripProcessingStatusResponse.Status.FAILED);
        assertThat(latestStatus()).isEqualTo("FAILED");
        assertThat(statuses.findStatus(trip.getId(), owner.getUserId()).status()).isEqualTo(TripProcessingStatusResponse.Status.FAILED);
        Long attachmentId = savedItem(batch).getTripAttachmentId();
        assertThat(complete(batch).orElseThrow().status()).isEqualTo(TripProcessingStatusResponse.Status.COMPLETED);
        assertThat(savedItem(batch).getTripAttachmentId()).isEqualTo(attachmentId);
        verify(derivatives).createAll(anyString(), anyList(), anyList());
        verify(storage, never()).delete(anyString());
        assertPhotoCount(1);
    }

    @Test
    void cleansFailedRetentionBeforeReleasingBatchForRetryAndKeepsOriginal() {
        var batch = prepare(1, 1, true);
        doThrow(new IllegalStateException("retain failed")).doNothing().when(storage).retain(anyList());
        assertThatThrownBy(() -> complete(batch)).hasMessage("retain failed");
        assertThat(latestStatus()).isEqualTo("FAILED");
        assertThat(savedItem(batch).getTripAttachmentId()).isNull();
        assertPhotoCount(0);
        String original = savedItem(batch).getObjectKey();
        verify(storage).delete(original + "-analyze");
        verify(storage).delete(original + "-preview");
        verify(storage, never()).delete(original);
        assertThat(prepare(1, 1, true).getUploadId()).isEqualTo(batch.getUploadId());
        assertThat(complete(batch).orElseThrow().status()).isEqualTo(TripProcessingStatusResponse.Status.COMPLETED);
        verify(derivatives, times(2)).createAll(anyString(), anyList(), anyList());
        assertPhotoCount(1);
    }

    @Test
    void blocksRetryWhenS3CleanupFailedInsteadOfDeletingDatabaseReferences() {
        var batch = prepare(1, 1, true);
        doThrow(new IllegalStateException("retain failed")).when(storage).retain(anyList());
        doThrow(new IllegalStateException("delete failed")).when(storage).delete(anyString());
        assertThatThrownBy(() -> complete(batch)).hasMessage("retain failed")
                .satisfies(error -> assertThat(error.getSuppressed()).hasSize(2));
        assertThat(latestStatus()).isEqualTo("PROCESSING");
        assertThat(savedItem(batch).getTripAttachmentId()).isNotNull();
        assertPhotoCount(1);
        assertThatThrownBy(() -> complete(batch)).isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
    }

    @Test
    void cleansPreviouslyPersistedDerivativesWhenRetryOriginalVerificationFails() {
        var batch = prepare(1, 1, true);
        doThrow(new IllegalStateException("ai unavailable")).when(analysis).analyze(anyLong(), anyString(), any(), any());
        assertThatThrownBy(() -> complete(batch)).hasMessage("ai unavailable");
        String original = savedItem(batch).getObjectKey();
        when(storage.size(original)).thenReturn(13L);
        assertThatThrownBy(() -> complete(batch)).isInstanceOf(InvalidAttachmentUploadException.class);
        verify(storage).delete(original + "-analyze");
        verify(storage).delete(original + "-preview");
        verify(storage, never()).delete(original);
        assertThat(latestStatus()).isEqualTo("FAILED");
        assertPhotoCount(0);
    }

    @Test
    void rejectsLateAiResultAfterCancellationAndDoesNotResurrectPhotos() {
        var batch = prepare(1, 1, true);
        doAnswer(call -> {
            assertThat(((BooleanSupplier) call.getArgument(3)).getAsBoolean()).isTrue();
            cancellation.cancel(trip.getId(), owner.getUserId());
            return result(call.getArgument(2));
        }).when(analysis).analyze(anyLong(), anyString(), any(), any());
        doReturn(Map.of()).when(names).resolve(anyLong(), anyString(), any(), any());
        assertThatThrownBy(() -> complete(batch)).isInstanceOf(TripNotFoundException.class);
        verify(analysis).cancel(trip.getId());
        var canceled = trips.findById(trip.getId()).orElseThrow();
        assertThat(canceled.getProcessingStatus()).isEqualTo(ProcessingStatus.CANCELED);
        assertThat(canceled.getDeletedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_attachments WHERE trip_id = ? AND deleted_at IS NULL",
                Integer.class, trip.getId())).isZero();
    }

    @Test
    void rollsBackTripAndBatchCompletionWhenAiResultCannotBePersisted() {
        var batch = prepare(1, 1, true);
        doAnswer(call -> {
            ((BooleanSupplier) call.getArgument(3)).getAsBoolean();
            var invalid = result(call.getArgument(2));
            ((tools.jackson.databind.node.ObjectNode) invalid.path("unclassified").get(0)).put("region_origin", "INVALID");
            return invalid;
        }).when(analysis).analyze(anyLong(), anyString(), any(), any());
        assertThatThrownBy(() -> complete(batch)).isInstanceOf(IllegalStateException.class);
        assertThat(latestStatus()).isEqualTo("FAILED");
        assertThat(trips.findById(trip.getId()).orElseThrow().getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSING);
        assertPhotoCount(1);
    }

    @Test
    void rejectsCompletionByAnotherOwnerBeforeContactingStorage() {
        var batch = prepare(1, 1, true);
        User other = users.save(new User(UUID.randomUUID() + "@yeodam.test", "다른회원"));
        assertThatThrownBy(() -> completion.complete(trip.getId(), other.getUserId(),
                new InitialAttachmentUploadCompleteRequest(batch.getUploadId())))
                .isInstanceOf(TripNotFoundException.class);
        verifyNoInteractions(storage, derivatives, analysis);
        assertThat(latestStatus()).isEqualTo("PENDING");
    }

    @Test
    void lateMultipartUploadCannotOverwriteNewDirectUploadBatch() {
        var reservation = legacy.reserveBatch(trip.getId(), owner.getUserId(), 1, 1);
        var batch = prepare(1, 1, true);
        var upload = new org.springframework.mock.web.MockMultipartFile("attachments[]", "photo.jpg", "image/jpeg", new byte[]{1});
        assertThatThrownBy(() -> legacy.saveFilesAndAttachments(trip.getId(), owner.getUserId(),
                reservation.executionId(), List.of(upload), List.of("old-original"), List.of("image/jpeg"),
                List.of(new DerivedPhotoKeys("old-original", "old-analyze", "old-preview"))))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        assertThatThrownBy(() -> legacy.failAndDeleteReference(trip.getId(), owner.getUserId(),
                reservation.executionId(), List.of(), List.of()))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        assertThatThrownBy(() -> results.saveCompleted(trip.getId(), owner.getUserId(),
                reservation.executionId(), List.of(), json.readTree("{\"places\":[],\"unclassified\":[]}"), Map.of()))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
        assertThat(latestStatus()).isEqualTo("PENDING");
        assertThat(trips.findById(trip.getId()).orElseThrow().getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSING);
        assertPhotoCount(0);
        assertThat(complete(batch).orElseThrow().status()).isEqualTo(TripProcessingStatusResponse.Status.COMPLETED);
    }

    private InitialAttachmentUploadBatch prepare(int number, int count, boolean last) {
        return urls.prepareBatch(trip.getId(), owner.getUserId(), new InitialAttachmentUploadUrlRequest(number, count, last,
                List.of(new InitialAttachmentUploadUrlRequest.Attachment("photo.jpg", "image/jpeg", 12L))));
    }

    private Optional<TripProcessingStatusResponse> complete(InitialAttachmentUploadBatch batch) {
        return completion.complete(trip.getId(), owner.getUserId(), new InitialAttachmentUploadCompleteRequest(batch.getUploadId()));
    }

    private InitialAttachmentUploadItem savedItem(InitialAttachmentUploadBatch batch) {
        return items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId()).getFirst();
    }

    private String latestStatus() {
        return batches.findFirstByTripIdAndUserIdOrderByIdDesc(trip.getId(), owner.getUserId()).orElseThrow().getStatus().name();
    }

    private void assertPhotoCount(int expected) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_attachments WHERE trip_id = ?", Integer.class, trip.getId()))
                .isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM files WHERE user_id = ?", Integer.class, owner.getUserId()))
                .isEqualTo(expected);
    }

    private JsonNode result(TripPhotoAnalysisRequest request) {
        var root = json.createObjectNode();
        root.putArray("places");
        var unclassified = root.putArray("unclassified");
        for (var photo : request.attachments()) {
            unclassified.addObject().put("trip_attachment_id", photo.tripAttachmentId())
                    .put("issue", "BLURRY").put("region_origin", "UNKNOWN").put("evaluation", 31);
        }
        return root;
    }
}
