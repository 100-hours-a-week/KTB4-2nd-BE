package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.integration.service.response.TripPhotoAnalysisStatusResponse;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class TripProcessingStatusServiceTest {
    private TripRepository trips;
    private TripDetailPlaceRepository places;
    private TripAttachmentRepository attachments;
    private TripPhotoAnalysisService analysis;
    private InitialUploadExecutionRegistry executions;
    private TripCreationExecutionRepository creationExecutions;
    private AdditionalAttachmentExecutionRepository additionalExecutions;
    private TripProcessingStatusService service;

    @BeforeEach
    void setUp() {
        trips = mock(TripRepository.class);
        places = mock(TripDetailPlaceRepository.class);
        attachments = mock(TripAttachmentRepository.class);
        analysis = mock(TripPhotoAnalysisService.class);
        executions = mock(InitialUploadExecutionRegistry.class);
        creationExecutions = mock(TripCreationExecutionRepository.class);
        additionalExecutions = mock(AdditionalAttachmentExecutionRepository.class);
        service = new TripProcessingStatusService(trips, places, attachments, analysis, executions, mock(InitialAttachmentUploadBatchRepository.class),
                creationExecutions, additionalExecutions,
                new TripProcessingStatusSyncService(creationExecutions, mock(EntityManager.class),
                        additionalExecutions, mock(ApplicationEventPublisher.class)));
    }

    @Test
    void 사진_추가_AI_상태가_변하면_저장된_버전도_증가한다() {
        var addition = publishedAddition();
        when(analysis.findStatus(7L, "addition"))
                .thenReturn(new TripPhotoAnalysisStatusResponse(7L,
                        TripPhotoAnalysisStatusResponse.Status.PROCESSING, null, "EMBEDDING", null, null))
                .thenReturn(new TripPhotoAnalysisStatusResponse(7L,
                        TripPhotoAnalysisStatusResponse.Status.COMPLETED, null, null, null, null));

        var processing = service.findStatus(7L, 1L);
        var finalizing = service.findStatus(7L, 1L);

        assertThat(processing.currentStep()).isEqualTo("EMBEDDING");
        assertThat(processing.statusVersion()).isEqualTo(2);
        assertThat(finalizing.status()).isEqualTo(Status.FINALIZING);
        assertThat(finalizing.statusVersion()).isEqualTo(3);
        assertThat(addition.getStatusVersion()).isEqualTo(finalizing.statusVersion());
    }

    @Test
    void 사진_추가_AI_조회_중_완료되면_늦은_응답으로_되돌리지_않는다() {
        var addition = publishedAddition();
        when(analysis.findStatus(7L, "addition")).thenAnswer(invocation -> {
            addition.finish(AdditionalAttachmentExecution.Status.COMPLETED, null, LocalDateTime.now());
            return new TripPhotoAnalysisStatusResponse(7L,
                    TripPhotoAnalysisStatusResponse.Status.PROCESSING, null, "EMBEDDING", null, null);
        });

        var response = service.findStatus(7L, 1L);

        assertThat(response.status()).isEqualTo(Status.COMPLETED);
        assertThat(response.statusVersion()).isEqualTo(addition.getStatusVersion());
        assertThat(response.currentStep()).isNull();
        assertThat(response.result()).isNotNull();
    }

    private AdditionalAttachmentExecution publishedAddition() {
        var addition = new AdditionalAttachmentExecution("addition", 7L, 1L, 2, LocalDateTime.now());
        addition.recordProcessPublished(LocalDateTime.now());
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.COMPLETED)));
        when(additionalExecutions.findFirstByTripIdAndUserIdOrderByCreatedAtDescExecutionIdDesc(7L, 1L))
                .thenReturn(Optional.of(addition));
        when(additionalExecutions.findForUpdate("addition")).thenReturn(Optional.of(addition));
        return addition;
    }

    @Test
    void V2_진행률의_전체_장수는_실행의_사진_수와_같아야_한다() {
        var execution = publishedExecution();
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.PROCESSING)));
        when(creationExecutions.findByTripId(7L)).thenReturn(Optional.of(execution));
        when(analysis.findStatus(7L, "execution")).thenReturn(new TripPhotoAnalysisStatusResponse(
                7L, TripPhotoAnalysisStatusResponse.Status.PROCESSING,
                new TripPhotoAnalysisStatusResponse.Progress(1, 99), "EMBEDDING", null, null));

        assertThrows(IllegalStateException.class, () -> service.findStatus(7L, 1L));
    }

    @Test
    void V2_AI_GET_후에는_DB를_다시_조회하지_않는다() {
        var execution = publishedExecution();
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.PROCESSING)));
        when(creationExecutions.findByTripId(7L)).thenReturn(Optional.of(execution));
        when(analysis.findStatus(7L, "execution")).thenReturn(new TripPhotoAnalysisStatusResponse(
                7L, TripPhotoAnalysisStatusResponse.Status.COMPLETED,
                new TripPhotoAnalysisStatusResponse.Progress(2, 2), null, null, null));

        assertThat(service.findStatus(7L, 1L).status()).isEqualTo(Status.FINALIZING);
        verify(trips, times(1)).findByIdAndUserIdAndDeletedAtIsNull(7L, 1L);
        verifyNoMoreInteractions(trips);
    }

    private TripCreationExecution publishedExecution() {
        var execution = new TripCreationExecution(
                "execution", 7L, 1L, 2);
        execution.recordProcessPublished(LocalDateTime.now());
        when(creationExecutions.findForUpdate("execution")).thenReturn(Optional.of(execution));
        return execution;
    }

    @Test
    void 생성_실행의_ID와_현재_버전을_반환한다() {
        var execution = publishedExecution();
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.PROCESSING)));
        when(creationExecutions.findByTripId(7L)).thenReturn(Optional.of(execution));
        when(analysis.findStatus(7L, "execution")).thenReturn(new TripPhotoAnalysisStatusResponse(
                7L, TripPhotoAnalysisStatusResponse.Status.QUEUED, null, null, null, null));

        var response = service.findStatus(7L, 1L);

        assertThat(response.executionId()).isEqualTo("execution");
        assertThat(response.statusVersion()).isEqualTo(execution.getStatusVersion());
        assertThat(response.statusVersion()).isEqualTo(1);
    }

    @Test
    void 완료된_생성도_저장된_실행_ID와_버전을_유지한다() {
        var execution = publishedExecution();
        execution.finish(TripCreationExecution.Status.COMPLETED, null, LocalDateTime.now());
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.COMPLETED)));
        when(creationExecutions.findByTripId(7L)).thenReturn(Optional.of(execution));

        var response = service.findStatus(7L, 1L);

        assertThat(response.executionId()).isEqualTo("execution");
        assertThat(response.statusVersion()).isEqualTo(2);
        verifyNoInteractions(analysis);
    }

    @Test
    void 실행_정보가_없는_기존_자료는_null과_0을_반환한다() {
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.COMPLETED)));

        var response = service.findStatus(7L, 1L);

        assertThat(response.executionId()).isNull();
        assertThat(response.statusVersion()).isZero();
    }

    @Test
    void AI_응답으로_공개_상태가_바뀌면_이전보다_큰_버전이어야_한다() {
        var execution = publishedExecution();
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.PROCESSING)));
        when(creationExecutions.findByTripId(7L)).thenReturn(Optional.of(execution));
        when(analysis.findStatus(7L, "execution"))
                .thenReturn(new TripPhotoAnalysisStatusResponse(
                        7L, TripPhotoAnalysisStatusResponse.Status.PROCESSING,
                        null, "EMBEDDING", null, null))
                .thenReturn(new TripPhotoAnalysisStatusResponse(
                        7L, TripPhotoAnalysisStatusResponse.Status.COMPLETED,
                        null, null, null, null));

        var processing = service.findStatus(7L, 1L);
        var finalizing = service.findStatus(7L, 1L);

        assertThat(processing.status()).isEqualTo(Status.PROCESSING);
        assertThat(finalizing.status()).isEqualTo(Status.FINALIZING);
        assertThat(finalizing.statusVersion()).isGreaterThan(processing.statusVersion());
    }

    @Test
    void 소유하지_않은_여행은_없는_여행과_같이_처리한다() {
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L)).thenReturn(Optional.empty());

        assertThrows(TripNotFoundException.class, () -> service.findStatus(7L, 1L));
        verifyNoInteractions(analysis);
    }

    @Test
    void 완료된_여행은_AI를_조회하지_않고_DB를_집계한다() {
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.COMPLETED)));
        when(places.countByTripIdAndDeletedAtIsNull(7L)).thenReturn(4L);
        when(attachments.countByTripIdAndDeletedAtIsNullAndClassificationStatus(
                7L, ClassificationStatus.ACTIVE)).thenReturn(11L);
        when(attachments.countByTripIdAndDeletedAtIsNullAndClassificationStatus(
                7L, ClassificationStatus.UNCLASSIFIED)).thenReturn(2L);

        var response = service.findStatus(7L, 1L);

        assertThat(response.status()).isEqualTo(Status.COMPLETED);
        assertThat(response.result().placeFolderCount()).isEqualTo(4);
        assertThat(response.result().classifiedAttachmentCount()).isEqualTo(11);
        assertThat(response.result().unclassifiedAttachmentCount()).isEqualTo(2);
        verifyNoInteractions(analysis);
    }

    @Test
    void AI_분석_시작_전에는_작업_단계_없이_처리중을_반환한다() {
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.PROCESSING)));

        var response = service.findStatus(7L, 1L);

        assertThat(response.status()).isEqualTo(Status.PROCESSING);
        assertThat(response.currentStep()).isNull();
        verifyNoInteractions(analysis);
    }

    @Test
    void AI가_처리중이면_상태와_작업_단계를_전달한다() {
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.PROCESSING)));
        when(executions.isAnalysisStarted(7L)).thenReturn(true);
        when(analysis.findStatus(7L)).thenReturn(new TripPhotoAnalysisStatusResponse(
                7L,
                TripPhotoAnalysisStatusResponse.Status.PROCESSING,
                new TripPhotoAnalysisStatusResponse.Progress(2, 5),
                "EMBEDDING",
                null,
                null
        ));

        var response = service.findStatus(7L, 1L);

        assertThat(response.status()).isEqualTo(Status.PROCESSING);
        assertThat(response.currentStep()).isEqualTo("EMBEDDING");
    }

    @Test
    void AI가_대기중이면_공개_상태를_진행중으로_유지한다() {
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.PROCESSING)));
        when(executions.isAnalysisStarted(7L)).thenReturn(true);
        when(analysis.findStatus(7L)).thenReturn(new TripPhotoAnalysisStatusResponse(
                7L,
                TripPhotoAnalysisStatusResponse.Status.QUEUED,
                null,
                null,
                null,
                null
        ));

        var response = service.findStatus(7L, 1L);

        assertThat(response.status()).isEqualTo(Status.PROCESSING);
        assertThat(response.currentStep()).isNull();
        assertThat(response.result()).isNull();
        assertThat(response.error()).isNull();
    }

    @Test
    void AI가_끝나고_DB가_처리중이면_후처리_상태를_반환한다() {
        Trip processing = trip(ProcessingStatus.PROCESSING);
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(processing));
        when(executions.isAnalysisStarted(7L)).thenReturn(true);
        when(analysis.findStatus(7L)).thenReturn(new TripPhotoAnalysisStatusResponse(
                7L,
                TripPhotoAnalysisStatusResponse.Status.COMPLETED,
                new TripPhotoAnalysisStatusResponse.Progress(5, 5),
                null,
                new ObjectMapper().createObjectNode(),
                null
        ));

        var response = service.findStatus(7L, 1L);

        assertThat(response.status()).isEqualTo(Status.FINALIZING);
        assertThat(response.currentStep()).isNull();
        assertThat(response.result()).isNull();
        assertThat(response.error()).isNull();
        verify(trips, times(2)).findByIdAndUserIdAndDeletedAtIsNull(7L, 1L);
    }

    @Test
    void AI_종료와_상태_조회_사이에_DB가_완료되면_DB_결과를_반환한다() {
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.PROCESSING)))
                .thenReturn(Optional.of(trip(ProcessingStatus.COMPLETED)));
        when(executions.isAnalysisStarted(7L)).thenReturn(true);
        when(analysis.findStatus(7L)).thenReturn(new TripPhotoAnalysisStatusResponse(
                7L,
                TripPhotoAnalysisStatusResponse.Status.COMPLETED,
                new TripPhotoAnalysisStatusResponse.Progress(5, 5),
                null,
                new ObjectMapper().createObjectNode(),
                null
        ));
        when(places.countByTripIdAndDeletedAtIsNull(7L)).thenReturn(1L);
        when(attachments.countByTripIdAndDeletedAtIsNullAndClassificationStatus(
                7L, ClassificationStatus.ACTIVE)).thenReturn(5L);

        var response = service.findStatus(7L, 1L);

        assertThat(response.status()).isEqualTo(Status.COMPLETED);
        assertThat(response.result().placeFolderCount()).isEqualTo(1);
        verify(trips, times(2)).findByIdAndUserIdAndDeletedAtIsNull(7L, 1L);
    }

    private Trip trip(ProcessingStatus status) {
        Trip trip = new Trip(1L, "여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "id", 7L);
        ReflectionTestUtils.setField(trip, "processingStatus", status);
        return trip;
    }
}
