package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.TripDisplayStatus;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import com.yeodam.yeodambe.trip.event.TripProcessingStatusChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionUploadRepository;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TripProcessingStatusSyncServiceTest {

    private final TripCreationExecutionRepository repository = mock(TripCreationExecutionRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final AdditionalAttachmentExecutionRepository additionalRepository =
            mock(AdditionalAttachmentExecutionRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final TripProcessingStatusSyncService service =
            new TripProcessingStatusSyncService(
                    repository,
                    entityManager,
                    additionalRepository,
                    eventPublisher,
                    mock(TripAnalysisExecutionRepository.class),
                    mock(TripAnalysisExecutionUploadRepository.class)
            );
    private final TripCreationExecution execution = new TripCreationExecution("execution", 7L, 42L, 2);

    @BeforeEach
    void setUp() {
        when(repository.findForUpdate("execution")).thenReturn(Optional.of(execution));
    }

    @Test
    void 버전이_일치하면_변경된_표시_상태를_적용한다() {
        var snapshot = service.synchronizeCreation("execution", 1, TripDisplayStatus.PROCESSING, "EMBEDDING");

        assertThat(snapshot.tripId()).isEqualTo(7L);
        assertThat(snapshot.executionId()).isEqualTo("execution");
        assertThat(snapshot.statusVersion()).isEqualTo(2);
        assertThat(snapshot.displayStatus()).isEqualTo(TripDisplayStatus.PROCESSING);
        assertThat(snapshot.currentStep()).isEqualTo("EMBEDDING");
        var order = inOrder(repository, entityManager);
        order.verify(repository).findForUpdate("execution");
        order.verify(entityManager).refresh(execution, LockModeType.PESSIMISTIC_WRITE);
        verify(eventPublisher).publishEvent(new TripProcessingStatusChangedEvent(42L, snapshot));
        verifyNoMoreInteractions(eventPublisher);
    }

    @Test
    void 표시_상태가_같으면_버전을_유지한다() {
        execution.updateDisplayStatus(TripDisplayStatus.PROCESSING, "EMBEDDING");

        var snapshot = service.synchronizeCreation("execution", 2, TripDisplayStatus.PROCESSING, "EMBEDDING");

        assertThat(snapshot.statusVersion()).isEqualTo(2);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void 이전_버전을_기준으로_한_AI_응답을_무시한다() {
        execution.updateDisplayStatus(TripDisplayStatus.PROCESSING, "CLUSTERING");

        var snapshot = service.synchronizeCreation("execution", 1, TripDisplayStatus.PROCESSING, "EMBEDDING");

        assertThat(snapshot.currentStep()).isEqualTo("CLUSTERING");
        assertThat(snapshot.statusVersion()).isEqualTo(2);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void 이전에_읽은_엔티티_대신_새로_읽은_상태와_비교한다() {
        doAnswer(invocation -> {
            execution.receiveResult("{}", "COMPLETED", LocalDateTime.of(2026, 10, 29, 12, 0), null);
            return null;
        }).when(entityManager).refresh(execution, LockModeType.PESSIMISTIC_WRITE);

        var snapshot = service.synchronizeCreation("execution", 1, TripDisplayStatus.PROCESSING, "EMBEDDING");

        assertThat(snapshot.displayStatus()).isEqualTo(TripDisplayStatus.FINALIZING);
        assertThat(snapshot.currentStep()).isNull();
        assertThat(snapshot.statusVersion()).isEqualTo(2);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void 버전이_일치해도_종료된_실행과_오류를_유지한다() {
        execution.finish(TripCreationExecution.Status.FAILED, "AI_PROCESSING_FAILED",
                LocalDateTime.of(2026, 10, 29, 12, 0));

        var snapshot = service.synchronizeCreation("execution", 2, TripDisplayStatus.PROCESSING, "EMBEDDING");

        assertThat(snapshot.displayStatus()).isEqualTo(TripDisplayStatus.FAILED);
        assertThat(snapshot.errorCode()).isEqualTo("AI_PROCESSING_FAILED");
        assertThat(snapshot.statusVersion()).isEqualTo(2);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void 실행이_없으면_어떤_상태도_새로_읽지_않고_거부한다() {
        when(repository.findForUpdate("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.synchronizeCreation("missing", 1, TripDisplayStatus.PROCESSING, null))
                .isInstanceOf(TripNotFoundException.class);
        verifyNoInteractions(entityManager);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void 추가_실행의_표시_상태를_적용하고_같은_상태는_유지한다() {
        var addition = additionalExecution();

        var changed = service.synchronizeAddition("addition", 1, TripDisplayStatus.PROCESSING, "EMBEDDING");
        var unchanged = service.synchronizeAddition("addition", 2, TripDisplayStatus.PROCESSING, "EMBEDDING");

        assertThat(changed.tripId()).isEqualTo(7L);
        assertThat(changed.executionId()).isEqualTo("addition");
        assertThat(changed.currentStep()).isEqualTo("EMBEDDING");
        assertThat(changed.statusVersion()).isEqualTo(2);
        assertThat(unchanged).isEqualTo(changed);
        verify(eventPublisher).publishEvent(new TripProcessingStatusChangedEvent(42L, changed));
        verifyNoMoreInteractions(eventPublisher);
        verify(entityManager, times(2)).refresh(addition, LockModeType.PESSIMISTIC_WRITE);
        verifyNoInteractions(repository);
    }

    @Test
    void 새로_읽은_뒤_오래된_추가_실행_응답을_무시한다() {
        var addition = additionalExecution();
        doAnswer(invocation -> {
            addition.updateDisplayStatus(TripDisplayStatus.FINALIZING, null);
            return null;
        }).when(entityManager).refresh(addition, LockModeType.PESSIMISTIC_WRITE);

        var snapshot = service.synchronizeAddition("addition", 1, TripDisplayStatus.PROCESSING, "EMBEDDING");

        assertThat(snapshot.displayStatus()).isEqualTo(TripDisplayStatus.FINALIZING);
        assertThat(snapshot.currentStep()).isNull();
        assertThat(snapshot.statusVersion()).isEqualTo(2);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void 추가_실행의_실패를_유지한다() {
        var addition = additionalExecution();
        addition.finish(AdditionalAttachmentExecution.Status.FAILED, "AI_PROCESSING_FAILED",
                LocalDateTime.of(2026, 10, 29, 12, 0));

        var snapshot = service.synchronizeAddition("addition", 2, TripDisplayStatus.PROCESSING, "EMBEDDING");

        assertThat(snapshot.displayStatus()).isEqualTo(TripDisplayStatus.FAILED);
        assertThat(snapshot.errorCode()).isEqualTo("AI_PROCESSING_FAILED");
        assertThat(snapshot.statusVersion()).isEqualTo(2);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void 존재하지_않는_추가_실행을_거부한다() {
        assertThatThrownBy(() -> service.synchronizeAddition("missing", 1, TripDisplayStatus.PROCESSING, null))
                .isInstanceOf(TripNotFoundException.class);
        verifyNoInteractions(entityManager);
        verifyNoInteractions(eventPublisher);
    }

    private AdditionalAttachmentExecution additionalExecution() {
        var addition = new AdditionalAttachmentExecution("addition", 7L, 42L, 2,
                LocalDateTime.of(2026, 10, 29, 12, 0));
        when(additionalRepository.findForUpdate("addition")).thenReturn(Optional.of(addition));
        return addition;
    }
}
