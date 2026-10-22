package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.UnsupportedAttachmentFormatException;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadCompleteRequest;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @ParameterizedTest
    @ValueSource(strings = {"size", "type", "unsupported"})
    void endsOnlyExecutionWhoseFileValidationFailedAndRethrowsOriginalError(String stage) {
        var transactions = mock(AdditionalAttachmentUploadTransactionService.class);
        var validator = mock(AttachmentUploadedFileValidator.class);
        var service = new AdditionalAttachmentUploadCompletionService(transactions, validator,
                mock(AttachmentDerivativePreparationService.class),
                mock(com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient.class),
                mock(AdditionalAttachmentAnalysisPreparationService.class));
        var batch = new AdditionalAttachmentUploadBatch("upload-id", "addition-id", 7L, 2L, 1, 1, true);
        var item = new AdditionalAttachmentUploadItem(batch, 1, "photo.jpg", "image/jpeg", 33L, "new-photo");
        when(transactions.loadForVerification(7L, 2L, "upload-id"))
                .thenReturn(new AdditionalAttachmentUploadTransactionService.VerificationBatch(
                        "addition-id", AdditionalAttachmentUploadStatus.PENDING, List.of(item), true));
        RuntimeException failure = stage.equals("unsupported")
                ? new UnsupportedAttachmentFormatException() : new InvalidAttachmentUploadException();
        if (stage.equals("size")) doThrow(failure).when(validator).verifySize("new-photo", 33L);
        else doThrow(failure).when(validator).verifyType("new-photo", "image/jpeg");

        assertThatThrownBy(() -> service.verifyBatch(7L, 2L,
                new AdditionalAttachmentUploadCompleteRequest("upload-id"))).isSameAs(failure);
        verify(transactions).failExecution("addition-id", stage.equals("unsupported")
                ? "UNSUPPORTED_ATTACHMENT_FORMAT" : "INVALID_ATTACHMENT_UPLOAD");
        verify(transactions, never()).markVerified(any(), any(), any());
    }

    @Test
    void doesNotEndExecutionWhenOwnershipLookupFails() {
        var transactions = mock(AdditionalAttachmentUploadTransactionService.class);
        var validator = mock(AttachmentUploadedFileValidator.class);
        var service = new AdditionalAttachmentUploadCompletionService(transactions, validator,
                mock(AttachmentDerivativePreparationService.class),
                mock(com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient.class),
                mock(AdditionalAttachmentAnalysisPreparationService.class));
        var failure = new InvalidAttachmentUploadException();
        when(transactions.loadForVerification(7L, 2L, "foreign-upload" )).thenThrow(failure);

        assertThatThrownBy(() -> service.verifyBatch(7L, 2L,
                new AdditionalAttachmentUploadCompleteRequest("foreign-upload"))).isSameAs(failure);
        verify(transactions, never()).failExecution(any(), any());
        verifyNoInteractions(validator);
    }

}
