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
import com.yeodam.yeodambe.trip.entity.TripDisplayStatus;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.yeodam.yeodambe.trip.fixture.TripMutationTestFixture;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadOutboxRepository;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionUploadRepository;
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
    private TripCreationExecutionRepository creationExecutions;
    private AdditionalAttachmentExecutionRepository additionalExecutions;
    private TripProcessingStatusService service;

    @Test
    void 생성_상태_조회는_AI_호출이나_버전_변경_없이_저장한_상태를_반환한다() {
        TripCreationExecution execution = publishedExecution();
        execution.updateDisplayStatus(TripDisplayStatus.PROCESSING, "SAVED_STEP");
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.PROCESSING)));
        when(creationExecutions.findByTripId(7L)).thenReturn(Optional.of(execution));
        when(analysis.findStatus(7L, "execution")).thenReturn(new TripPhotoAnalysisStatusResponse(
                7L, TripPhotoAnalysisStatusResponse.Status.PROCESSING, null, "UNSAVED_STEP", null, null));

        TripProcessingStatusResponse response = service.findStatus(7L, 1L);

        assertThat(response.currentStep()).isEqualTo("SAVED_STEP");
        assertThat(response.statusVersion()).isEqualTo(2);
        assertThat(execution.getStatusVersion()).isEqualTo(2);
        verifyNoInteractions(analysis);
        verify(creationExecutions, never()).findForUpdate(anyString());
    }

    @Test
    void 추가_상태_조회는_AI_호출이나_버전_변경_없이_저장한_상태를_반환한다() {
        AdditionalAttachmentExecution execution = publishedAddition();
        execution.updateDisplayStatus(TripDisplayStatus.PROCESSING, "SAVED_STEP");
        when(analysis.findStatus(7L, "addition")).thenReturn(new TripPhotoAnalysisStatusResponse(
                7L, TripPhotoAnalysisStatusResponse.Status.PROCESSING, null, "UNSAVED_STEP", null, null));

        TripProcessingStatusResponse response = service.findStatus(7L, 1L);

        assertThat(response.currentStep()).isEqualTo("SAVED_STEP");
        assertThat(response.statusVersion()).isEqualTo(2);
        assertThat(execution.getStatusVersion()).isEqualTo(2);
        verifyNoInteractions(analysis, creationExecutions);
        verify(additionalExecutions, never()).findForUpdate(anyString());
    }

    @BeforeEach
    void setUp() {
        trips = mock(TripRepository.class);
        places = mock(TripDetailPlaceRepository.class);
        attachments = mock(TripAttachmentRepository.class);
        analysis = mock(TripPhotoAnalysisService.class);
        creationExecutions = mock(TripCreationExecutionRepository.class);
        additionalExecutions = mock(AdditionalAttachmentExecutionRepository.class);
        TripProcessingStatusSyncService synchronization = new TripProcessingStatusSyncService(
                creationExecutions,
                mock(EntityManager.class),
                additionalExecutions,
                mock(ApplicationEventPublisher.class),
                mock(TripAnalysisExecutionRepository.class),
                mock(TripAnalysisExecutionUploadRepository.class)
        );
        service = new TripProcessingStatusService(
                trips,
                places,
                attachments,
                analysis,
                mock(InitialAttachmentUploadBatchRepository.class),
                creationExecutions,
                additionalExecutions,
                synchronization,
                mock(TripCompletionSnapshotFactory.class),
                mock(TripAnalysisExecutionRepository.class),
                mock(TripAnalysisExecutionUploadRepository.class),
                mock(TripEditAccessService.class),
                TripMutationTestFixture.direct(),
                mock(AdditionalAttachmentUploadOutboxRepository.class),
                new ObjectMapper()
        );
    }

    @Test
    void 사진_추가_AI_상태가_변하면_저장된_버전도_증가한다() {
        AdditionalAttachmentExecution addition = publishedAddition();
        when(analysis.findStatus(7L, "addition"))
                .thenReturn(new TripPhotoAnalysisStatusResponse(7L,
                        TripPhotoAnalysisStatusResponse.Status.PROCESSING, null, "EMBEDDING", null, null))
                .thenReturn(new TripPhotoAnalysisStatusResponse(7L,
                        TripPhotoAnalysisStatusResponse.Status.COMPLETED, null, null, null, null));

        service.synchronizeStatus(7L, 1L);
        TripProcessingStatusResponse processing = service.findStatus(7L, 1L);
        service.synchronizeStatus(7L, 1L);
        TripProcessingStatusResponse finalizing = service.findStatus(7L, 1L);

        assertThat(processing.currentStep()).isEqualTo("EMBEDDING");
        assertThat(processing.statusVersion()).isEqualTo(2);
        assertThat(finalizing.status()).isEqualTo(Status.FINALIZING);
        assertThat(finalizing.statusVersion()).isEqualTo(3);
        assertThat(addition.getStatusVersion()).isEqualTo(finalizing.statusVersion());
    }

    @Test
    void 사진_추가_AI_조회_중_완료되면_늦은_응답으로_되돌리지_않는다() {
        AdditionalAttachmentExecution addition = publishedAddition();
        when(analysis.findStatus(7L, "addition")).thenAnswer(invocation -> {
            addition.finish(AdditionalAttachmentExecution.Status.COMPLETED, null, LocalDateTime.now());
            return new TripPhotoAnalysisStatusResponse(7L,
                    TripPhotoAnalysisStatusResponse.Status.PROCESSING, null, "EMBEDDING", null, null);
        });

        service.synchronizeStatus(7L, 1L);
        TripProcessingStatusResponse response = service.findStatus(7L, 1L);

        assertThat(response.status()).isEqualTo(Status.COMPLETED);
        assertThat(response.statusVersion()).isEqualTo(addition.getStatusVersion());
        assertThat(response.currentStep()).isNull();
        assertThat(response.result()).isNotNull();
    }

    private AdditionalAttachmentExecution publishedAddition() {
        AdditionalAttachmentExecution addition = new AdditionalAttachmentExecution("addition", 7L, 1L, 2, LocalDateTime.now());
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
        TripCreationExecution execution = publishedExecution();
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.PROCESSING)));
        when(creationExecutions.findByTripId(7L)).thenReturn(Optional.of(execution));
        when(analysis.findStatus(7L, "execution")).thenReturn(new TripPhotoAnalysisStatusResponse(
                7L, TripPhotoAnalysisStatusResponse.Status.PROCESSING,
                new TripPhotoAnalysisStatusResponse.Progress(1, 99), "EMBEDDING", null, null));

        assertThrows(IllegalStateException.class, () -> service.synchronizeStatus(7L, 1L));
        verify(creationExecutions, never()).findForUpdate(anyString());
    }

    @Test
    void 추가_진행률의_전체_장수가_다르면_상태를_갱신하지_않는다() {
        AdditionalAttachmentExecution execution = publishedAddition();
        when(analysis.findStatus(7L, "addition")).thenReturn(new TripPhotoAnalysisStatusResponse(
                7L, TripPhotoAnalysisStatusResponse.Status.PROCESSING,
                new TripPhotoAnalysisStatusResponse.Progress(1, 99), "EMBEDDING", null, null));

        assertThrows(IllegalStateException.class, () -> service.synchronizeStatus(7L, 1L));

        assertThat(execution.getStatusVersion()).isEqualTo(1);
        verify(additionalExecutions, never()).findForUpdate(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"TERMINAL", "APPLYING", "UNPUBLISHED"})
    void 생성_실행의_종료_결과반영_미발행_상태는_AI를_조회하지_않는다(String state) {
        TripCreationExecution execution = new TripCreationExecution("execution", 7L, 1L, 2);
        if (!state.equals("UNPUBLISHED")) {
            execution.recordProcessPublished(LocalDateTime.now());
        }
        if (state.equals("TERMINAL")) {
            execution.finish(TripCreationExecution.Status.COMPLETED, null, LocalDateTime.now());
        } else if (state.equals("APPLYING")) {
            execution.receiveResult("{}", "COMPLETED", LocalDateTime.now(), null);
        }
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.PROCESSING)));
        when(creationExecutions.findByTripId(7L)).thenReturn(Optional.of(execution));
        when(creationExecutions.findForUpdate("execution")).thenReturn(Optional.of(execution));
        long version = execution.getStatusVersion();

        service.synchronizeStatus(7L, 1L);

        assertThat(execution.getStatusVersion()).isEqualTo(version);
        verifyNoInteractions(analysis);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TERMINAL", "APPLYING", "UNPUBLISHED"})
    void 추가_실행의_종료_결과반영_미발행_상태는_AI를_조회하지_않는다(String state) {
        AdditionalAttachmentExecution execution = new AdditionalAttachmentExecution(
                "addition", 7L, 1L, 2, LocalDateTime.now());
        if (!state.equals("UNPUBLISHED")) {
            execution.recordProcessPublished(LocalDateTime.now());
        }
        if (state.equals("TERMINAL")) {
            execution.finish(AdditionalAttachmentExecution.Status.COMPLETED, null, LocalDateTime.now());
        } else if (state.equals("APPLYING")) {
            execution.receiveResult("{}", "COMPLETED", LocalDateTime.now());
        }
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.COMPLETED)));
        when(additionalExecutions.findFirstByTripIdAndUserIdOrderByCreatedAtDescExecutionIdDesc(7L, 1L))
                .thenReturn(Optional.of(execution));
        when(additionalExecutions.findForUpdate("addition")).thenReturn(Optional.of(execution));
        long version = execution.getStatusVersion();

        service.synchronizeStatus(7L, 1L);

        assertThat(execution.getStatusVersion()).isEqualTo(version);
        verifyNoInteractions(analysis, creationExecutions);
    }

    @Test
    void AI_동기화는_진행상태를_저장하고_여행을_다시_조회하지_않는다() {
        TripCreationExecution execution = publishedExecution();
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.PROCESSING)));
        when(creationExecutions.findByTripId(7L)).thenReturn(Optional.of(execution));
        when(analysis.findStatus(7L, "execution")).thenReturn(new TripPhotoAnalysisStatusResponse(
                7L, TripPhotoAnalysisStatusResponse.Status.COMPLETED,
                new TripPhotoAnalysisStatusResponse.Progress(2, 2), null, null, null));

        service.synchronizeStatus(7L, 1L);

        assertThat(execution.getDisplayStatus()).isEqualTo(TripDisplayStatus.FINALIZING);
        verify(trips, times(1)).findByIdAndUserIdAndDeletedAtIsNull(7L, 1L);
        verifyNoMoreInteractions(trips);
    }

    private TripCreationExecution publishedExecution() {
        TripCreationExecution execution = new TripCreationExecution(
                "execution", 7L, 1L, 2);
        execution.recordProcessPublished(LocalDateTime.now());
        when(creationExecutions.findForUpdate("execution")).thenReturn(Optional.of(execution));
        return execution;
    }

    @Test
    void 생성_실행의_ID와_현재_버전을_반환한다() {
        TripCreationExecution execution = publishedExecution();
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.PROCESSING)));
        when(creationExecutions.findByTripId(7L)).thenReturn(Optional.of(execution));
        when(analysis.findStatus(7L, "execution")).thenReturn(new TripPhotoAnalysisStatusResponse(
                7L, TripPhotoAnalysisStatusResponse.Status.QUEUED, null, null, null, null));

        TripProcessingStatusResponse response = service.findStatus(7L, 1L);

        assertThat(response.executionId()).isEqualTo("execution");
        assertThat(response.statusVersion()).isEqualTo(execution.getStatusVersion());
        assertThat(response.statusVersion()).isEqualTo(1);
    }

    @Test
    void 완료된_생성도_저장된_실행_ID와_버전을_유지한다() {
        TripCreationExecution execution = publishedExecution();
        execution.finish(TripCreationExecution.Status.COMPLETED, null, LocalDateTime.now());
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.COMPLETED)));
        when(creationExecutions.findByTripId(7L)).thenReturn(Optional.of(execution));

        TripProcessingStatusResponse response = service.findStatus(7L, 1L);

        assertThat(response.executionId()).isEqualTo("execution");
        assertThat(response.statusVersion()).isEqualTo(2);
        verifyNoInteractions(analysis);
    }

    @Test
    void 실행_정보가_없는_기존_자료는_null과_0을_반환한다() {
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.COMPLETED)));

        TripProcessingStatusResponse response = service.findStatus(7L, 1L);

        assertThat(response.executionId()).isNull();
        assertThat(response.statusVersion()).isZero();
    }

    @Test
    void AI_응답으로_공개_상태가_바뀌면_이전보다_큰_버전이어야_한다() {
        TripCreationExecution execution = publishedExecution();
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

        service.synchronizeStatus(7L, 1L);
        TripProcessingStatusResponse processing = service.findStatus(7L, 1L);
        service.synchronizeStatus(7L, 1L);
        TripProcessingStatusResponse finalizing = service.findStatus(7L, 1L);

        assertThat(processing.status()).isEqualTo(Status.PROCESSING);
        assertThat(finalizing.status()).isEqualTo(Status.FINALIZING);
        assertThat(finalizing.statusVersion()).isGreaterThan(processing.statusVersion());
    }

    @Test
    void 소유하지_않은_여행은_없는_여행과_같이_처리한다() {
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L)).thenReturn(Optional.empty());

        assertThrows(TripNotFoundException.class, () -> service.findStatus(7L, 1L));
        verifyNoInteractions(analysis, creationExecutions, additionalExecutions);
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

        TripProcessingStatusResponse response = service.findStatus(7L, 1L);

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

        TripProcessingStatusResponse response = service.findStatus(7L, 1L);

        assertThat(response.status()).isEqualTo(Status.PROCESSING);
        assertThat(response.currentStep()).isNull();
        verifyNoInteractions(analysis);
    }

    @Test
    void 실행_정보가_없는_처리중_자료는_AI_단계를_조회하지_않는다() {
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.PROCESSING)));

        TripProcessingStatusResponse response = service.findStatus(7L, 1L);

        assertThat(response.status()).isEqualTo(Status.PROCESSING);
        assertThat(response.currentStep()).isNull();
        assertThat(response.executionId()).isNull();
        assertThat(response.statusVersion()).isZero();
        verifyNoInteractions(analysis);
    }

    @Test
    void 실행_정보가_없는_실패한_자료는_DB_실패를_반환한다() {
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.FAILED)));

        TripProcessingStatusResponse response = service.findStatus(7L, 1L);

        assertThat(response.status()).isEqualTo(Status.FAILED);
        assertThat(response.error().code()).isEqualTo("AI_PROCESSING_FAILED");
        assertThat(response.currentStep()).isNull();
        assertThat(response.result()).isNull();
        verifyNoInteractions(analysis);
    }

    @Test
    void 실행_정보가_없는_취소된_자료는_DB_취소를_반환한다() {
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.CANCELED)));

        TripProcessingStatusResponse response = service.findStatus(7L, 1L);

        assertThat(response.status()).isEqualTo(Status.CANCELED);
        assertThat(response.currentStep()).isNull();
        assertThat(response.result()).isNull();
        assertThat(response.error()).isNull();
        verifyNoInteractions(analysis);
    }

    @Test
    void 실행_정보가_없는_완료된_자료는_DB_결과를_반환한다() {
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.COMPLETED)));
        when(places.countByTripIdAndDeletedAtIsNull(7L)).thenReturn(1L);
        when(attachments.countByTripIdAndDeletedAtIsNullAndClassificationStatus(
                7L, ClassificationStatus.ACTIVE)).thenReturn(5L);

        TripProcessingStatusResponse response = service.findStatus(7L, 1L);

        assertThat(response.status()).isEqualTo(Status.COMPLETED);
        assertThat(response.result().placeFolderCount()).isEqualTo(1);
        verify(trips).findByIdAndUserIdAndDeletedAtIsNull(7L, 1L);
        verifyNoInteractions(analysis);
    }

    private Trip trip(ProcessingStatus status) {
        Trip trip = new Trip(1L, "여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "id", 7L);
        ReflectionTestUtils.setField(trip, "processingStatus", status);
        return trip;
    }
}
