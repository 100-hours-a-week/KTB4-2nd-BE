package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
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
    @Autowired private UserRepository userRepository;
    @Autowired private TripRepository tripRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private TripAttachmentStorageClient storage;

    private Long userId;
    private Long tripId;
    private String additionId;

    @BeforeEach
    void setUp() {
        userId = userRepository.save(new User(UUID.randomUUID() + "@yeodam.test", "URL회원")).getUserId();
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
