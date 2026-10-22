package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.UnsupportedAttachmentFormatException;
import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import com.yeodam.yeodambe.common.exception.AttachmentUploadLimitExceededException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadCompleteRequest;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    void includesExistingPhotosInTwoHundredPhotoLimitBeforeIssuingUrls() {
        String key = "existing/" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO files (user_id, original_file_name, object_key, mime_type, original_size_bytes) "
                + "VALUES (?, 'old.jpg', ?, 'image/jpeg', 1024)", userId, key);
        Long fileId = jdbcTemplate.queryForObject("SELECT file_id FROM files WHERE object_key = ?", Long.class, key);
        jdbcTemplate.update("INSERT INTO trip_attachments (trip_id, file_id, region_origin, issue, "
                + "analyze_storage_key, preview_storage_key) VALUES (?, ?, 'UNKNOWN', 'NONE', ?, ?)",
                tripId, fileId, key + "-analyze", key + "-preview");
        var declared = request();
        var overLimit = new AdditionalAttachmentUploadUrlRequest(additionId, 1, 200, false, declared.attachments());
        assertThatThrownBy(() -> service.issueUploadUrls(tripId, userId, overLimit))
                .isInstanceOf(AttachmentUploadLimitExceededException.class);
        assertThat(batchCount()).isZero();
        assertThat(itemCount()).isZero();
        verifyNoInteractions(storage);

        var exactLimit = new AdditionalAttachmentUploadUrlRequest(additionId, 1, 199, false, declared.attachments());
        assertThat(service.issueUploadUrls(tripId, userId, exactLimit).attachments()).hasSize(2);
        assertThat(batchCount()).isEqualTo(1);
        assertThat(tripAttachmentCount()).isEqualTo(1);
    }

    @Test
    void refusesOtherOwnerAndDeletedTripBeforeSavingBatchOrIssuingUrls() {
        Long otherUserId = userRepository.save(new User(UUID.randomUUID() + "@yeodam.test", "다른회원")).getUserId();
        assertThatThrownBy(() -> service.issueUploadUrls(tripId, otherUserId, request()))
                .isInstanceOf(TripNotFoundException.class);
        jdbcTemplate.update("UPDATE trips SET deleted_at = CURRENT_TIMESTAMP(6) WHERE trip_id = ?", tripId);
        assertThatThrownBy(() -> service.issueUploadUrls(tripId, userId, request()))
                .isInstanceOf(TripNotFoundException.class);
        assertThat(batchCount()).isZero();
        assertThat(itemCount()).isZero();
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCESSING", "FAILED"})
    void refusesTripThatHasNotCompletedBeforeSavingBatchOrIssuingUrls(String processingStatus) {
        jdbcTemplate.update("UPDATE trips SET processing_status = ? WHERE trip_id = ?", processingStatus, tripId);
        assertThatThrownBy(() -> service.issueUploadUrls(tripId, userId, request()))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        assertThat(batchCount()).isZero();
        assertThat(itemCount()).isZero();
        verifyNoInteractions(storage);
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
        assertThat(executionCount()).isEqualTo(1);
        assertThat(executionReservedBytes()).isEqualTo(3072L);
        assertThat(memberReservedBytes()).isEqualTo(3072L);
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
        assertThat(executionCount()).isZero();
        assertThat(memberReservedBytes()).isZero();
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
    void createsUploadingExecutionWithDeclaredCountAndDatabaseActivityTime() {
        service.issueUploadUrls(tripId, userId, request());

        Map<String, Object> execution = jdbcTemplate.queryForMap(
                "SELECT trip_id, user_id, status, total_attachment_count, input_closed, "
                        + "last_upload_activity_at, analysis_attachment_count "
                        + "FROM additional_attachment_executions WHERE execution_id = ?", additionId);
        assertThat(((Number) execution.get("trip_id")).longValue()).isEqualTo(tripId);
        assertThat(((Number) execution.get("user_id")).longValue()).isEqualTo(userId);
        assertThat(execution.get("status")).isEqualTo("UPLOADING");
        assertThat(((Number) execution.get("total_attachment_count")).intValue()).isEqualTo(2);
        assertThat(execution.get("analysis_attachment_count")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT input_closed FROM additional_attachment_executions WHERE execution_id = ?",
                Boolean.class, additionId)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_upload_activity_at FROM additional_attachment_executions WHERE execution_id = ?",
                java.time.LocalDateTime.class, additionId)).isNotNull();
        assertThat(executionReservedBytes()).isEqualTo(3072L);
        assertThat(memberReservedBytes()).isEqualTo(3072L);
    }

    @Test
    void addsOnlyNewBatchBytesToSameExecutionAndMemberReservation() {
        var first = new AdditionalAttachmentUploadUrlRequest(additionId, 1, 3, false, request().attachments());
        var firstResponse = service.issueUploadUrls(tripId, userId, first);
        jdbcTemplate.update("UPDATE additional_attachment_upload_batches SET status = 'VERIFIED' WHERE upload_id = ?",
                firstResponse.uploadId());
        var second = new AdditionalAttachmentUploadUrlRequest(additionId, 2, 3, true, List.of(
                new AdditionalAttachmentUploadUrlRequest.Attachment("third.jpg", "image/jpeg", 4096L)));

        var secondResponse = service.issueUploadUrls(tripId, userId, second);
        var repeated = service.issueUploadUrls(tripId, userId, second);

        assertThat(repeated.uploadId()).isEqualTo(secondResponse.uploadId());
        assertThat(executionCount()).isEqualTo(1);
        assertThat(batchCount()).isEqualTo(2);
        assertThat(itemCount()).isEqualTo(3);
        assertThat(executionReservedBytes()).isEqualTo(7168L);
        assertThat(memberReservedBytes()).isEqualTo(7168L);
    }

    @Test
    void rollsBackNewExecutionWhenMemberCapacityIsInsufficient() {
        jdbcTemplate.update("UPDATE user_stats SET storage_maximum_bytes = 3071 WHERE user_id = ?", userId);

        assertThatThrownBy(() -> service.issueUploadUrls(tripId, userId, request()))
                .isInstanceOf(AttachmentUploadLimitExceededException.class);

        assertThat(executionCount()).isZero();
        assertThat(batchCount()).isZero();
        assertThat(itemCount()).isZero();
        assertThat(memberReservedBytes()).isZero();
        verifyNoInteractions(storage);
    }

    @Test
    void refreshesActivityOnPendingBatchReissueWithoutReservingAgain() {
        service.issueUploadUrls(tripId, userId, request());
        jdbcTemplate.update("UPDATE additional_attachment_executions "
                + "SET last_upload_activity_at = '2020-01-01 00:00:00' WHERE execution_id = ?", additionId);

        service.issueUploadUrls(tripId, userId, request());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_upload_activity_at FROM additional_attachment_executions WHERE execution_id = ?",
                java.time.LocalDateTime.class, additionId)).isAfter(java.time.LocalDateTime.of(2020, 1, 1, 0, 0));
        assertThat(executionReservedBytes()).isEqualTo(3072L);
        assertThat(memberReservedBytes()).isEqualTo(3072L);
    }

    @Test
    void rejectsNewBatchAfterInputClosedAndRejectsReissueAfterExecutionEnded() {
        var first = new AdditionalAttachmentUploadUrlRequest(additionId, 1, 3, false, request().attachments());
        var response = service.issueUploadUrls(tripId, userId, first);
        jdbcTemplate.update("UPDATE additional_attachment_upload_batches SET status = 'VERIFIED' WHERE upload_id = ?",
                response.uploadId());
        jdbcTemplate.update("UPDATE additional_attachment_executions SET input_closed = true WHERE execution_id = ?",
                additionId);
        clearInvocations(storage);
        var next = new AdditionalAttachmentUploadUrlRequest(additionId, 2, 3, true, List.of(
                new AdditionalAttachmentUploadUrlRequest.Attachment("third.jpg", "image/jpeg", 4096L)));

        assertThatThrownBy(() -> service.issueUploadUrls(tripId, userId, next))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        jdbcTemplate.update("UPDATE additional_attachment_executions SET status = 'FAILED', "
                + "ended_at = CURRENT_TIMESTAMP(6) WHERE execution_id = ?", additionId);
        assertThatThrownBy(() -> service.issueUploadUrls(tripId, userId, first))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);

        assertThat(batchCount()).isEqualTo(1);
        assertThat(memberReservedBytes()).isEqualTo(3072L);
        verifyNoInteractions(storage);
    }

    private long executionCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM additional_attachment_executions WHERE execution_id = ?", Long.class, additionId);
    }

    private long executionReservedBytes() {
        return jdbcTemplate.queryForObject(
                "SELECT reserved_bytes FROM additional_attachment_executions WHERE execution_id = ?", Long.class, additionId);
    }

    private long memberReservedBytes() {
        return jdbcTemplate.queryForObject(
                "SELECT storage_reserved_bytes FROM user_stats WHERE user_id = ?", Long.class, userId);
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
    void endsExecutionWhenLaterFileSizeIsWrongAndAllowsNewAddition() {
        var response = service.issueUploadUrls(tripId, userId, request());
        configureUploadedFiles();
        String secondKey = jdbcTemplate.queryForObject(
                "SELECT object_key FROM additional_attachment_upload_items WHERE object_key LIKE ? AND file_order = 2",
                String.class, "trip-additions/" + additionId + "/original/%");
        doReturn(999L).when(storage).size(secondKey);
        var completeRequest = new AdditionalAttachmentUploadCompleteRequest(response.uploadId());

        assertThatThrownBy(() -> completionService.verifyBatch(tripId, userId, completeRequest))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertFailedExecutionAndReleasedReservation(response.uploadId(), "INVALID_ATTACHMENT_UPLOAD");
        assertThat(itemCount()).isEqualTo(2);
        transactionService.failExecution(additionId, "INVALID_ATTACHMENT_UPLOAD");
        assertThat(userStatsRepository.findByUser_UserId(userId).orElseThrow().getStorageReservedBytes()).isZero();
        assertThatThrownBy(() -> completionService.verifyBatch(tripId, userId, completeRequest))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);

        additionId = UUID.randomUUID().toString();
        var retry = service.issueUploadUrls(tripId, userId, request());
        completionService.verifyBatch(tripId, userId,
                new AdditionalAttachmentUploadCompleteRequest(retry.uploadId()));
        assertThat(batchStatus(retry.uploadId())).isEqualTo("VERIFIED");
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

    @ParameterizedTest
    @ValueSource(strings = {"unsupported", "mismatch"})
    void endsExecutionOnInvalidFileHeaderWithoutWaitingForTimeout(String kind) {
        var issued = service.issueUploadUrls(tripId, userId, request());
        configureUploadedFiles();
        doAnswer(call -> new ByteArrayInputStream(
                kind.equals("unsupported") ? "broken photo".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        : new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}))
                .when(storage).open(anyString());

        assertThatThrownBy(() -> completionService.verifyBatch(tripId, userId,
                new AdditionalAttachmentUploadCompleteRequest(issued.uploadId())))
                .isInstanceOf(kind.equals("unsupported") ? UnsupportedAttachmentFormatException.class
                        : InvalidAttachmentUploadException.class);
        assertFailedExecutionAndReleasedReservation(issued.uploadId(),
                kind.equals("unsupported") ? "UNSUPPORTED_ATTACHMENT_FORMAT" : "INVALID_ATTACHMENT_UPLOAD");
    }

    @Test
    void transientS3FailureKeepsExecutionAndReservationForRetry() {
        var issued = service.issueUploadUrls(tripId, userId, request());
        configureUploadedFiles();
        doThrow(S3Exception.builder().statusCode(503).message("unavailable").build()).when(storage).size(anyString());

        assertThatThrownBy(() -> completionService.verifyBatch(tripId, userId,
                new AdditionalAttachmentUploadCompleteRequest(issued.uploadId())))
                .isInstanceOf(AttachmentStorageException.class);
        assertThat(batchStatus(issued.uploadId())).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("SELECT ended_at FROM additional_attachment_executions WHERE execution_id = ?",
                java.time.LocalDateTime.class, additionId)).isNull();
        assertThat(userStatsRepository.findByUser_UserId(userId).orElseThrow().getStorageReservedBytes()).isEqualTo(3072L);

        doAnswer(call -> jdbcTemplate.queryForObject(
                "SELECT size_bytes FROM additional_attachment_upload_items WHERE object_key = ?",
                Long.class, call.getArgument(0, String.class))).when(storage).size(anyString());
        completionService.verifyBatch(tripId, userId, new AdditionalAttachmentUploadCompleteRequest(issued.uploadId()));
        assertThat(batchStatus(issued.uploadId())).isEqualTo("VERIFIED");
    }

    private void assertFailedExecutionAndReleasedReservation(String uploadId, String code) {
        assertThat(batchStatus(uploadId)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM additional_attachment_executions WHERE execution_id = ?",
                String.class, additionId)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("SELECT ended_at FROM additional_attachment_executions WHERE execution_id = ?",
                java.time.LocalDateTime.class, additionId)).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT error_code FROM additional_attachment_executions WHERE execution_id = ?",
                String.class, additionId)).isEqualTo(code);
        assertThat(userStatsRepository.findByUser_UserId(userId).orElseThrow().getStorageReservedBytes()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM additional_attachment_upload_items WHERE object_key LIKE ? AND cleanup_started_at IS NOT NULL",
                Integer.class, originalKeyPattern())).isEqualTo(2);
        assertThat(tripAttachmentCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM additional_attachment_upload_outbox WHERE trip_id = ?", Integer.class, tripId)).isZero();
        verify(storage, never()).delete(anyString());
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
