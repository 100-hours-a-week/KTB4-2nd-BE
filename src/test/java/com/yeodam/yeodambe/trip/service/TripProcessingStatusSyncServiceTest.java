package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.TripDisplayStatus;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    private final TripProcessingStatusSyncService service =
            new TripProcessingStatusSyncService(repository, entityManager, additionalRepository);
    private final TripCreationExecution execution = new TripCreationExecution("execution", 7L, 42L, 2);

    @BeforeEach
    void setUp() {
        when(repository.findForUpdate("execution")).thenReturn(Optional.of(execution));
    }

    @Test
    void appliesAChangedDisplayStateWhenTheVersionMatches() {
        var snapshot = service.synchronizeCreation("execution", 0, TripDisplayStatus.PROCESSING, "EMBEDDING");

        assertThat(snapshot.tripId()).isEqualTo(7L);
        assertThat(snapshot.executionId()).isEqualTo("execution");
        assertThat(snapshot.statusVersion()).isEqualTo(1);
        assertThat(snapshot.displayStatus()).isEqualTo(TripDisplayStatus.PROCESSING);
        assertThat(snapshot.currentStep()).isEqualTo("EMBEDDING");
        var order = inOrder(repository, entityManager);
        order.verify(repository).findForUpdate("execution");
        order.verify(entityManager).refresh(execution, LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void preservesTheVersionWhenTheDisplayStateIsUnchanged() {
        execution.updateDisplayStatus(TripDisplayStatus.PROCESSING, "EMBEDDING");

        var snapshot = service.synchronizeCreation("execution", 1, TripDisplayStatus.PROCESSING, "EMBEDDING");

        assertThat(snapshot.statusVersion()).isEqualTo(1);
    }

    @Test
    void ignoresAnAiResponseBasedOnAnOlderVersion() {
        execution.updateDisplayStatus(TripDisplayStatus.PROCESSING, "CLUSTERING");

        var snapshot = service.synchronizeCreation("execution", 0, TripDisplayStatus.PROCESSING, "EMBEDDING");

        assertThat(snapshot.currentStep()).isEqualTo("CLUSTERING");
        assertThat(snapshot.statusVersion()).isEqualTo(1);
    }

    @Test
    void comparesAgainstTheRefreshedStateInsteadOfThePreviouslyReadEntity() {
        doAnswer(invocation -> {
            execution.receiveResult("{}", "COMPLETED", LocalDateTime.of(2026, 10, 29, 12, 0));
            return null;
        }).when(entityManager).refresh(execution, LockModeType.PESSIMISTIC_WRITE);

        var snapshot = service.synchronizeCreation("execution", 0, TripDisplayStatus.PROCESSING, "EMBEDDING");

        assertThat(snapshot.displayStatus()).isEqualTo(TripDisplayStatus.FINALIZING);
        assertThat(snapshot.currentStep()).isNull();
        assertThat(snapshot.statusVersion()).isEqualTo(1);
    }

    @Test
    void preservesAFinishedExecutionAndItsErrorEvenWhenTheVersionMatches() {
        execution.finish(TripCreationExecution.Status.FAILED, "AI_PROCESSING_FAILED",
                LocalDateTime.of(2026, 10, 29, 12, 0));

        var snapshot = service.synchronizeCreation("execution", 1, TripDisplayStatus.PROCESSING, "EMBEDDING");

        assertThat(snapshot.displayStatus()).isEqualTo(TripDisplayStatus.FAILED);
        assertThat(snapshot.errorCode()).isEqualTo("AI_PROCESSING_FAILED");
        assertThat(snapshot.statusVersion()).isEqualTo(1);
    }

    @Test
    void rejectsAMissingExecutionWithoutRefreshingAnything() {
        when(repository.findForUpdate("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.synchronizeCreation("missing", 0, TripDisplayStatus.PROCESSING, null))
                .isInstanceOf(TripNotFoundException.class);
        verifyNoInteractions(entityManager);
    }

    @Test
    void appliesAndThenPreservesAnAdditionalExecutionDisplayState() {
        var addition = additionalExecution();

        var changed = service.synchronizeAddition("addition", 0, TripDisplayStatus.PROCESSING, "EMBEDDING");
        var unchanged = service.synchronizeAddition("addition", 1, TripDisplayStatus.PROCESSING, "EMBEDDING");

        assertThat(changed.tripId()).isEqualTo(7L);
        assertThat(changed.executionId()).isEqualTo("addition");
        assertThat(changed.currentStep()).isEqualTo("EMBEDDING");
        assertThat(changed.statusVersion()).isEqualTo(1);
        assertThat(unchanged).isEqualTo(changed);
        verify(entityManager, times(2)).refresh(addition, LockModeType.PESSIMISTIC_WRITE);
        verifyNoInteractions(repository);
    }

    @Test
    void ignoresAnOlderAdditionalExecutionResponseAfterRefresh() {
        var addition = additionalExecution();
        doAnswer(invocation -> {
            addition.updateDisplayStatus(TripDisplayStatus.FINALIZING, null);
            return null;
        }).when(entityManager).refresh(addition, LockModeType.PESSIMISTIC_WRITE);

        var snapshot = service.synchronizeAddition("addition", 0, TripDisplayStatus.PROCESSING, "EMBEDDING");

        assertThat(snapshot.displayStatus()).isEqualTo(TripDisplayStatus.FINALIZING);
        assertThat(snapshot.currentStep()).isNull();
        assertThat(snapshot.statusVersion()).isEqualTo(1);
    }

    @Test
    void preservesAnAdditionalExecutionFailure() {
        var addition = additionalExecution();
        addition.finish(AdditionalAttachmentExecution.Status.FAILED, "AI_PROCESSING_FAILED",
                LocalDateTime.of(2026, 10, 29, 12, 0));

        var snapshot = service.synchronizeAddition("addition", 1, TripDisplayStatus.PROCESSING, "EMBEDDING");

        assertThat(snapshot.displayStatus()).isEqualTo(TripDisplayStatus.FAILED);
        assertThat(snapshot.errorCode()).isEqualTo("AI_PROCESSING_FAILED");
        assertThat(snapshot.statusVersion()).isEqualTo(1);
    }

    @Test
    void rejectsAMissingAdditionalExecution() {
        assertThatThrownBy(() -> service.synchronizeAddition("missing", 0, TripDisplayStatus.PROCESSING, null))
                .isInstanceOf(TripNotFoundException.class);
        verifyNoInteractions(entityManager);
    }

    private AdditionalAttachmentExecution additionalExecution() {
        var addition = new AdditionalAttachmentExecution("addition", 7L, 42L, 2,
                LocalDateTime.of(2026, 10, 29, 12, 0));
        when(additionalRepository.findForUpdate("addition")).thenReturn(Optional.of(addition));
        return addition;
    }
}
