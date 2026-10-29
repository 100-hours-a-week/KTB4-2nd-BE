package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.integration.config.KafkaTopicProperties;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.entity.TripDisplayStatus;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse.Status;
import com.yeodam.yeodambe.user.service.UserStatsService;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InitialUploadAcceptanceStatusTest {
    private final InitialAttachmentUploadTransactionService service =
            new InitialAttachmentUploadTransactionService(
                    mock(TripRepository.class), mock(InitialAttachmentUploadBatchRepository.class),
                    mock(InitialAttachmentUploadItemRepository.class), mock(StoredFileRepository.class),
                    mock(TripAttachmentRepository.class), mock(TripCreationExecutionRepository.class),
                    mock(UserStatsService.class), mock(ObjectMapper.class), mock(TripRegionRepository.class),
                    mock(EntityManager.class), mock(JdbcTemplate.class), mock(KafkaTopicProperties.class),
                    mock(TripCreationOutboxRepository.class), mock(ApplicationEventPublisher.class));

    @Test
    void returnsTheStoredDisplayStateStepAndVersion() {
        var execution = new TripCreationExecution("execution", 7L, 1L, 2);
        execution.updateDisplayStatus(TripDisplayStatus.PROCESSING, "EMBEDDING");
        var trip = trip();

        var processing = service.acceptanceStatus(trip, execution);
        execution.receiveResult("{}", "COMPLETED", LocalDateTime.now());
        var finalizing = service.acceptanceStatus(trip, execution);

        assertThat(processing.executionId()).isEqualTo("execution");
        assertThat(processing.status()).isEqualTo(Status.PROCESSING);
        assertThat(processing.currentStep()).isEqualTo("EMBEDDING");
        assertThat(processing.statusVersion()).isEqualTo(2);
        assertThat(finalizing.status()).isEqualTo(Status.FINALIZING);
        assertThat(finalizing.currentStep()).isNull();
        assertThat(finalizing.statusVersion()).isEqualTo(3);
    }

    @Test
    void returnsTheStoredFailureCode() {
        var execution = new TripCreationExecution("execution", 7L, 1L, 2);
        execution.finish(TripCreationExecution.Status.FAILED, "AI_PROCESSING_FAILED", LocalDateTime.now());

        var response = service.acceptanceStatus(trip(), execution);

        assertThat(response.status()).isEqualTo(Status.FAILED);
        assertThat(response.statusVersion()).isEqualTo(execution.getStatusVersion());
        assertThat(response.error().code()).isEqualTo("AI_PROCESSING_FAILED");
    }

    @Test
    void continuesToRejectCanceledUploads() {
        var execution = new TripCreationExecution("execution", 7L, 1L, 2);
        execution.finish(TripCreationExecution.Status.CANCELED, null, LocalDateTime.now());
        var trip = trip();

        assertThatThrownBy(() -> service.acceptanceStatus(trip, execution))
                .isInstanceOf(TripInitialAttachmentUploadNotAllowedException.class);
    }

    private Trip trip() {
        var trip = mock(Trip.class);
        when(trip.getId()).thenReturn(7L);
        return trip;
    }
}
