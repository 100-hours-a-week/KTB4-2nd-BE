package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadCompleteRequest;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AdditionalAttachmentUploadUrlIntegrationTest {
    @Autowired private AdditionalAttachmentUploadUrlService service;
    @Autowired private AdditionalAttachmentUploadCompletionService completionService;
    @Autowired private AdditionalAttachmentUploadTransactionService transactionService;
    @Autowired private UserRepository userRepository;
    @Autowired private TripRepository tripRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private TripAttachmentStorageClient storage;

    private Long userId;
    private Long tripId;
    private String additionId;

    @Autowired private com.yeodam.yeodambe.user.repository.UserStatsRepository userStatsRepository;

    private void newStatsForOwner() {
        userStatsRepository.save(new com.yeodam.yeodambe.user.entity.UserStats(userRepository.findById(userId).orElseThrow()));
    }

    @BeforeEach
    void setUp() {
        userId = userRepository.save(new User(UUID.randomUUID() + "@yeodam.test", "URL회원")).getUserId();
        newStatsForOwner();
        tripId = tripRepository.save(new Trip(userId, "추가 URL 여행", LocalDate.now(), LocalDate.now())).getId();
        jdbcTemplate.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", tripId);
        additionId = UUID.randomUUID().toString();
        when(storage.createUploadUrl(anyString(), anyString(), eq(Duration.ofMinutes(10))))
                .thenAnswer(call -> "https://example.test/" + call.getArgument(0));
    }

    @Test
    void issuesOrderedUrlsAndReusesCommittedBatchAndKeysOnRepeat() {
        OffsetDateTime earliest = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10);
        var response = service.issueUploadUrls(tripId, userId, request());
        OffsetDateTime latest = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10);

        assertThat(response.attachments()).extracting(a -> a.fileName())
                .containsExactly("same.jpg", "same.jpg");
        assertThat(response.attachments().get(0).headers())
                .isEqualTo(Map.of("Content-Type", "image/jpeg", "If-None-Match", "*"));
        assertThat(response.attachments().get(1).headers())
                .isEqualTo(Map.of("Content-Type", "image/png", "If-None-Match", "*"));
        assertThat(response.attachments()).allSatisfy(a -> {
            assertThat(a.method()).isEqualTo("PUT");
            assertThat(a.expiresAt()).isBetween(earliest, latest);
        });
        assertThat(response.attachments()).extracting(a -> a.uploadUrl()).doesNotHaveDuplicates();
        var repeated = service.issueUploadUrls(tripId, userId, request());
        assertThat(repeated.uploadId()).isEqualTo(response.uploadId());
        assertThat(repeated.attachments()).extracting(a -> a.uploadUrl())
                .containsExactlyElementsOf(response.attachments().stream().map(a -> a.uploadUrl()).toList());
        assertThat(batchCount()).isEqualTo(1);
        assertThat(itemCount()).isEqualTo(2);
        verify(storage, times(4)).createUploadUrl(anyString(), anyString(), eq(Duration.ofMinutes(10)));
    }

    @Test
    void rollsBackNewBatchAndFilesWhenSecondUrlCannotBeIssued() {
        when(storage.createUploadUrl(anyString(), anyString(), eq(Duration.ofMinutes(10))))
                .thenReturn("https://example.test/first")
                .thenThrow(new IllegalStateException("test presign failure"));

        assertThatThrownBy(() -> service.issueUploadUrls(tripId, userId, request()))
                .isInstanceOf(IllegalStateException.class).hasMessage("test presign failure");
        assertThat(batchCount()).isZero();
        assertThat(itemCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT processing_status FROM trips WHERE trip_id = ?", String.class, tripId))
                .isEqualTo("COMPLETED");
    }

    @Test
    void refusesNewUrlsForBatchAlreadyVerified() {
        var response = service.issueUploadUrls(tripId, userId, request());
        jdbcTemplate.update("UPDATE additional_attachment_upload_batches SET status = 'VERIFIED' WHERE upload_id = ?",
                response.uploadId());
        clearInvocations(storage);

        assertThatThrownBy(() -> service.issueUploadUrls(tripId, userId, request()))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        verifyNoInteractions(storage);
        assertThat(batchCount()).isEqualTo(1);
        assertThat(itemCount()).isEqualTo(2);
    }

    private AdditionalAttachmentUploadUrlRequest request() {
        return new AdditionalAttachmentUploadUrlRequest(additionId, 1, 2, true, List.of(
                new AdditionalAttachmentUploadUrlRequest.Attachment("same.jpg", "image/jpeg", 1024L),
                new AdditionalAttachmentUploadUrlRequest.Attachment("same.jpg", "image/png", 2048L)));
    }

    @Test
    void verifiesEveryFileOutsideTransactionAndPersistsStateOnce() {
        var response = service.issueUploadUrls(tripId, userId, request());
        configureUploadedFiles();
        clearInvocations(storage);
        var completeRequest = new AdditionalAttachmentUploadCompleteRequest(response.uploadId());

        completionService.verifyBatch(tripId, userId, completeRequest);

        assertThat(batchStatus(response.uploadId())).isEqualTo("VERIFIED");
        verify(storage, times(2)).size(anyString());
        verify(storage, times(2)).open(anyString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT processing_status FROM trips WHERE trip_id = ?", String.class, tripId))
                .isEqualTo("COMPLETED");
        clearInvocations(storage);
        completionService.verifyBatch(tripId, userId, completeRequest);
        verifyNoInteractions(storage);
        assertThat(batchCount()).isEqualTo(1);
        assertThat(itemCount()).isEqualTo(2);
    }

    @Test
    void keepsPendingWhenLaterFileSizeIsWrongAndAllowsVerificationRetry() {
        var response = service.issueUploadUrls(tripId, userId, request());
        configureUploadedFiles();
        String secondKey = jdbcTemplate.queryForObject(
                "SELECT object_key FROM additional_attachment_upload_items WHERE object_key LIKE ? AND file_order = 2",
                String.class, "trip-additions/" + additionId + "/original/%");
        doReturn(999L).when(storage).size(secondKey);
        var completeRequest = new AdditionalAttachmentUploadCompleteRequest(response.uploadId());

        assertThatThrownBy(() -> completionService.verifyBatch(tripId, userId, completeRequest))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThat(batchStatus(response.uploadId())).isEqualTo("PENDING");
        assertThat(itemCount()).isEqualTo(2);

        doReturn(2048L).when(storage).size(secondKey);
        completionService.verifyBatch(tripId, userId, completeRequest);
        assertThat(batchStatus(response.uploadId())).isEqualTo("VERIFIED");
    }

    @Test
    void rejectsUploadIdOfAnotherTripBeforeAccessingStorage() {
        var response = service.issueUploadUrls(tripId, userId, request());
        Long otherTripId = tripRepository.save(
                new Trip(userId, "다른 완료 여행", LocalDate.now(), LocalDate.now())).getId();
        jdbcTemplate.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", otherTripId);
        clearInvocations(storage);

        assertThatThrownBy(() -> completionService.verifyBatch(otherTripId, userId,
                new AdditionalAttachmentUploadCompleteRequest(response.uploadId())))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        verifyNoInteractions(storage);
        assertThat(batchStatus(response.uploadId())).isEqualTo("PENDING");
    }

    @Test
    void rejectsMissingUploadIdAndAlreadyProcessingBatch() {
        assertThatThrownBy(() -> completionService.verifyBatch(tripId, userId, null))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThatThrownBy(() -> completionService.verifyBatch(tripId, userId,
                new AdditionalAttachmentUploadCompleteRequest(" ")))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        var response = service.issueUploadUrls(tripId, userId, request());
        jdbcTemplate.update("UPDATE additional_attachment_upload_batches SET status = 'PROCESSING' WHERE upload_id = ?",
                response.uploadId());
        clearInvocations(storage);
        assertThatThrownBy(() -> completionService.verifyBatch(tripId, userId,
                new AdditionalAttachmentUploadCompleteRequest(response.uploadId())))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        verifyNoInteractions(storage);
        assertThat(batchStatus(response.uploadId())).isEqualTo("PROCESSING");
    }

    private String batchStatus(String uploadId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM additional_attachment_upload_batches WHERE upload_id = ?", String.class, uploadId);
    }

    @Test
    void savesFilesAttachmentsAndOffsetsAndReusesTheirIdsOnRepeat() {
        String uploadId = verifiedBatch();
        var derived = derivedPhotos();

        var saved = transactionService.saveAttachments(tripId, userId, uploadId, derived);

        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(a -> a.getAnalyzeStorageKey())
                .containsExactly(derived.get(0).analyzeKey(), derived.get(1).analyzeKey());
        assertThat(saved).allSatisfy(a -> {
            assertThat(a.getTripId()).isEqualTo(tripId);
            assertThat(a.getAnalyzeSizeBytes()).isEqualTo(100L);
            assertThat(a.getPreviewSizeBytes()).isEqualTo(50L);
            assertThat(a.getTakenAt()).isEqualTo(java.time.LocalDateTime.parse("2026-10-07T12:00:00"));
        });
        assertThat(jdbcTemplate.queryForList(
                "SELECT trip_attachment_id FROM additional_attachment_upload_items WHERE object_key LIKE ? ORDER BY file_order",
                Long.class, originalKeyPattern())).containsExactlyElementsOf(saved.stream().map(a -> a.getId()).toList());
        assertThat(jdbcTemplate.queryForList(
                "SELECT taken_at_with_offset FROM additional_attachment_upload_items WHERE object_key LIKE ? ORDER BY file_order",
                String.class, originalKeyPattern())).containsExactly("2026-10-07T12:00+09:00", "2026-10-07T12:00+09:00");
        assertThat(jdbcTemplate.queryForList(
                "SELECT original_size_bytes FROM files WHERE object_key LIKE ? ORDER BY original_size_bytes",
                Long.class, originalKeyPattern())).containsExactly(1024L, 2048L);

        var repeated = transactionService.saveAttachments(tripId, userId, uploadId, derived);
        assertThat(repeated).extracting(a -> a.getId()).containsExactlyElementsOf(saved.stream().map(a -> a.getId()).toList());
        assertThat(storedFileCount()).isEqualTo(2);
        assertThat(tripAttachmentCount()).isEqualTo(2);
        assertThat(batchStatus(uploadId)).isEqualTo("VERIFIED");
    }

    @Test
    void rollsBackFirstPhotoWhenSecondPhotoOriginalKeyIsWrong() {
        String uploadId = verifiedBatch();
        var derived = derivedPhotos();
        var wrong = new DerivedPhotoKeys("wrong-original", "wrong-analyze", "wrong-preview");

        assertThatThrownBy(() -> transactionService.saveAttachments(
                tripId, userId, uploadId, List.of(derived.get(0), wrong)))
                .isInstanceOf(IllegalStateException.class);

        assertNoPhotosPersisted(uploadId);
    }

    @Test
    void rollsBackFirstPhotoWhenSecondPhotoSizeMetadataIsMissing() {
        String uploadId = verifiedBatch();
        var derived = derivedPhotos();
        var incomplete = new DerivedPhotoKeys(derived.get(1).originalKey(), "analyze", "preview");

        assertThatThrownBy(() -> transactionService.saveAttachments(
                tripId, userId, uploadId, List.of(derived.get(0), incomplete)))
                .isInstanceOf(IllegalStateException.class);

        assertNoPhotosPersisted(uploadId);
    }

    @Test
    void rejectsAttachmentSaveBeforeS3Verification() {
        var response = service.issueUploadUrls(tripId, userId, request());
        assertThatThrownBy(() -> transactionService.saveAttachments(
                tripId, userId, response.uploadId(), derivedPhotos()))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        assertThat(storedFileCount()).isZero();
        assertThat(tripAttachmentCount()).isZero();
        assertThat(batchStatus(response.uploadId())).isEqualTo("PENDING");
    }

    private String verifiedBatch() {
        var response = service.issueUploadUrls(tripId, userId, request());
        configureUploadedFiles();
        completionService.verifyBatch(tripId, userId, new AdditionalAttachmentUploadCompleteRequest(response.uploadId()));
        return response.uploadId();
    }

    private List<DerivedPhotoKeys> derivedPhotos() {
        List<String> keys = jdbcTemplate.queryForList(
                "SELECT object_key FROM additional_attachment_upload_items WHERE object_key LIKE ? ORDER BY file_order",
                String.class, originalKeyPattern());
        return java.util.stream.IntStream.range(0, keys.size()).mapToObj(index -> new DerivedPhotoKeys(
                keys.get(index), keys.get(index) + "-analyze", keys.get(index) + "-preview", null,
                OffsetDateTime.parse("2026-10-07T12:00:00+09:00"),
                java.math.BigDecimal.ONE, java.math.BigDecimal.TEN, "camera",
                index == 0 ? 1024L : 2048L, 100L, 50L, null)).toList();
    }

    private String originalKeyPattern() {
        return "trip-additions/" + additionId + "/original/%";
    }

    private int storedFileCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM files WHERE object_key LIKE ?",
                Integer.class, originalKeyPattern());
    }

    private int tripAttachmentCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trip_attachments WHERE trip_id = ?",
                Integer.class, tripId);
    }

    private void assertNoPhotosPersisted(String uploadId) {
        assertThat(storedFileCount()).isZero();
        assertThat(tripAttachmentCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM additional_attachment_upload_items WHERE object_key LIKE ? AND trip_attachment_id IS NOT NULL",
                Integer.class, originalKeyPattern())).isZero();
        assertThat(batchStatus(uploadId)).isEqualTo("VERIFIED");
    }

    private void configureUploadedFiles() {
        when(storage.size(anyString())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            return jdbcTemplate.queryForObject(
                    "SELECT size_bytes FROM additional_attachment_upload_items WHERE object_key = ?",
                    Long.class, call.getArgument(0, String.class));
        });
        when(storage.open(anyString())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            String type = jdbcTemplate.queryForObject(
                    "SELECT content_type FROM additional_attachment_upload_items WHERE object_key = ?",
                    String.class, call.getArgument(0, String.class));
            byte[] header = "image/jpeg".equals(type)
                    ? new byte[]{(byte) 255, (byte) 216, (byte) 255}
                    : new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
            return new ByteArrayInputStream(header);
        });
    }

    private int batchCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM additional_attachment_upload_batches WHERE addition_id = ?",
                Integer.class, additionId);
    }

    private int itemCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM additional_attachment_upload_items WHERE object_key LIKE ?",
                Integer.class, "trip-additions/" + additionId + "/original/%");
    }
}
