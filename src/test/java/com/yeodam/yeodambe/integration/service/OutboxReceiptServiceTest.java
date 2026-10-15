package com.yeodam.yeodambe.integration.service;

import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;
import com.yeodam.yeodambe.story.service.StoryGenerationPublicationStateService;
import com.yeodam.yeodambe.trip.service.InitialAttachmentUploadTransactionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OutboxReceiptServiceTest {
    private final InitialAttachmentUploadTransactionService trips = mock(InitialAttachmentUploadTransactionService.class);
    private final StoryGenerationPublicationStateService stories = mock(StoryGenerationPublicationStateService.class);
    private final OutboxReceiptService service = new OutboxReceiptService(trips, stories);
    private final OutboxReceipt receipt = new OutboxReceipt("requests", "7", "{}", Instant.now());

    @Test
    void dispatchesToTheSourceDomainWithoutChangingReceipt() {
        service.observe("trip_creation_outbox", "trip-event", receipt);
        service.observe("story_generation_outbox", "12", receipt);

        verify(trips).recordPublished("trip-event", receipt);
        verify(stories).recordPublished(12L, receipt);
        verifyNoMoreInteractions(trips, stories);
    }

    @Test
    void rejectsUnregisteredSourcesWithoutConfirmingAnything() {
        assertThatThrownBy(() -> service.observe("additional_attachment_upload_outbox", "12", receipt))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(trips, stories);
    }
}
