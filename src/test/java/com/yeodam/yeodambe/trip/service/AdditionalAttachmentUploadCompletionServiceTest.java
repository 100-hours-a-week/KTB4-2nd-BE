package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AdditionalAttachmentUploadCompletionServiceTest {
    @Test
    void passesOnlySuppliedBatchKeysAndTypesToSharedPreparation() {
        var preparation = mock(AttachmentDerivativePreparationService.class);
        var transactions = mock(AdditionalAttachmentUploadTransactionService.class);
        var validator = mock(AttachmentUploadedFileValidator.class);
        var service = new AdditionalAttachmentUploadCompletionService(transactions, validator, preparation, mock(com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient.class), mock(AdditionalAttachmentAnalysisPreparationService.class));
        var batch = new AdditionalAttachmentUploadBatch("upload-id", "addition-id", 7L, 2L, 1, 2, true);
        var items = List.of(
                new AdditionalAttachmentUploadItem(batch, 1, "one.jpg", "image/jpeg", 1024L, "new-one"),
                new AdditionalAttachmentUploadItem(batch, 2, "two.png", "image/png", 2048L, "new-two"));
        var photos = List.of(
                new DerivedPhotoKeys("new-one", "analyze-one", "preview-one"),
                new DerivedPhotoKeys("new-two", "analyze-two", "preview-two"));
        when(preparation.create("upload-id", List.of("new-one", "new-two"),
                List.of("image/jpeg", "image/png"))).thenReturn(photos);

        assertThat(service.createDerived("upload-id", items)).isSameAs(photos);
        verify(preparation).create("upload-id", List.of("new-one", "new-two"), List.of("image/jpeg", "image/png"));
        verifyNoInteractions(transactions, validator);
    }
}
