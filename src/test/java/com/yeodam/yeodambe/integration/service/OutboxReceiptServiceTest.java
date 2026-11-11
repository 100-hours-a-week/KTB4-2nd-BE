package com.yeodam.yeodambe.integration.service;

import com.yeodam.yeodambe.integration.service.response.WorkflowTraceCarrier;
import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;
import com.yeodam.yeodambe.story.service.StoryGenerationPublicationStateService;
import com.yeodam.yeodambe.trip.service.InitialAttachmentUploadTransactionService;
import com.yeodam.yeodambe.trip.service.AdditionalAttachmentUploadOutboxService;
import com.yeodam.yeodambe.trip.entity.TripCreationOutbox;
import com.yeodam.yeodambe.trip.fixture.TripMutationTestFixture;
import com.yeodam.yeodambe.trip.repository.TripCreationOutboxRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OutboxReceiptServiceTest {
    private final InitialAttachmentUploadTransactionService trips = mock(InitialAttachmentUploadTransactionService.class);
    private final StoryGenerationPublicationStateService stories = mock(StoryGenerationPublicationStateService.class);
    private final AdditionalAttachmentUploadOutboxService additions = mock(AdditionalAttachmentUploadOutboxService.class);
    private final TripCreationOutboxRepository outboxes = mock(TripCreationOutboxRepository.class);
    private final OutboxReceiptService service = new OutboxReceiptService(
            trips, additions, stories, TripMutationTestFixture.direct(), outboxes
    );
    private final OutboxReceipt receipt = new OutboxReceipt("requests", "7", "{}", Instant.now());

    @Test
    void 발행_확인_정보를_바꾸지_않고_원본_도메인에_전달한다() {
        TripCreationOutbox outbox = new TripCreationOutbox(
                "trip-event", "execution", "process", 0, 7L, "requests", "{}",
                new WorkflowTraceCarrier(null, null)
        );
        when(outboxes.findById("trip-event")).thenReturn(Optional.of(outbox));
        service.observe("trip_creation_outbox", "trip-event", receipt);
        service.observe("story_generation_outbox", "12", receipt);
        service.observe("additional_attachment_upload_outbox", "12", receipt);

        verify(trips).recordPublished("trip-event", receipt);
        verify(stories).recordPublished(12L, receipt);
        verify(additions).recordPublished(12L, receipt);
        verifyNoMoreInteractions(trips, stories, additions);
    }

    @Test
    void 없는_최초_Outbox는_기존_누락_예외를_유지하고_도메인을_변경하지_않는다() {
        assertThatThrownBy(() -> service.observe("trip_creation_outbox", "missing", receipt))
                .isExactlyInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(trips, stories, additions);
    }

    @Test
    void 등록되지_않은_출처는_아무것도_확인_처리하지_않고_거부한다() {
        assertThatThrownBy(() -> service.observe("unregistered_source", "12", receipt))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(trips, stories, additions);
    }
}
