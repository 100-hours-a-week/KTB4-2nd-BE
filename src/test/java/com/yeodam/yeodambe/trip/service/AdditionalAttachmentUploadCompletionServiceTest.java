package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.AttachmentConversionRoute;
import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem;
import org.junit.jupiter.api.Test;

import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.UnsupportedAttachmentFormatException;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadCompleteRequest;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AdditionalAttachmentUploadCompletionServiceTest {
    @Test
    void 전달된_배치의_키와_형식만_공유_준비_로직에_전달한다() {
        AttachmentDerivativePreparationService preparation = mock(AttachmentDerivativePreparationService.class);
        AdditionalAttachmentUploadTransactionService transactions = mock(AdditionalAttachmentUploadTransactionService.class);
        AttachmentUploadedFileValidator validator = mock(AttachmentUploadedFileValidator.class);
        AdditionalAttachmentUploadCompletionService service = new AdditionalAttachmentUploadCompletionService(
                transactions, validator, preparation, mock(TripAttachmentStorageClient.class),
                mock(AdditionalAttachmentAnalysisPreparationService.class), coordinator()
        );
        AdditionalAttachmentUploadBatch batch = new AdditionalAttachmentUploadBatch("upload-id", "addition-id", 7L, 2L, 1, 2, true);
        List<AdditionalAttachmentUploadItem> items = List.of(
                new AdditionalAttachmentUploadItem(batch, 1, "one.jpg", "image/jpeg", 1024L, "new-one"),
                new AdditionalAttachmentUploadItem(batch, 2, "two.png", "image/png", 2048L, "new-two"));
        List<DerivedPhotoKeys> photos = List.of(
                new DerivedPhotoKeys("new-one", "analyze-one", "preview-one"),
                new DerivedPhotoKeys("new-two", "analyze-two", "preview-two"));
        when(preparation.create("upload-id", List.of("new-one", "new-two"),
                List.of("image/jpeg", "image/png"))).thenReturn(photos);

        assertThat(service.createDerived("upload-id", items)).isSameAs(photos);
        verify(preparation).create("upload-id", List.of("new-one", "new-two"), List.of("image/jpeg", "image/png"));
        verifyNoInteractions(transactions, validator);
    }
    @ParameterizedTest
    @CsvSource({
            "HOLD,VERIFIED", "KAFKA,VERIFIED", "HOLD,PREPARED", "KAFKA,PREPARED", "HOLD,COMPLETED", "KAFKA,COMPLETED"
    })
    void DB_외의_경로는_기존_준비_로직을_사용할_수_없다(String route, String status) {
        AdditionalAttachmentUploadTransactionService transactions = mock(AdditionalAttachmentUploadTransactionService.class);
        AttachmentDerivativePreparationService preparation = mock(AttachmentDerivativePreparationService.class);
        AdditionalAttachmentAnalysisPreparationService analysis = mock(AdditionalAttachmentAnalysisPreparationService.class);
        AdditionalAttachmentUploadCompletionService service = new AdditionalAttachmentUploadCompletionService(transactions,
                mock(AttachmentUploadedFileValidator.class), preparation,
                mock(TripAttachmentStorageClient.class), analysis, coordinator());
        when(transactions.loadForVerification(7L, 2L, "upload-id"))
                .thenReturn(new AdditionalAttachmentUploadTransactionService.VerificationBatch(
                        "addition-id", AdditionalAttachmentUploadStatus.valueOf(status), List.of(), false,
                        AttachmentConversionRoute.valueOf(route)));
        AdditionalAttachmentUploadCompleteRequest request = new AdditionalAttachmentUploadCompleteRequest("upload-id");

        assertThatThrownBy(() -> service.prepareBatchPhotos(7L, 2L, request))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        assertThatThrownBy(() -> service.prepareQueueBatch(7L, 2L, request))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        verifyNoInteractions(preparation, analysis);
    }

    @ParameterizedTest
    @ValueSource(strings = {"size", "type", "unsupported"})
    void 파일_검증이_실패한_실행만_종료하고_원래_오류를_다시_던진다(String stage) {
        AdditionalAttachmentUploadTransactionService transactions = mock(AdditionalAttachmentUploadTransactionService.class);
        AttachmentUploadedFileValidator validator = mock(AttachmentUploadedFileValidator.class);
        AdditionalAttachmentUploadCompletionService service = new AdditionalAttachmentUploadCompletionService(transactions, validator,
                mock(AttachmentDerivativePreparationService.class),
                mock(TripAttachmentStorageClient.class),
                mock(AdditionalAttachmentAnalysisPreparationService.class), coordinator());
        AdditionalAttachmentUploadBatch batch = new AdditionalAttachmentUploadBatch("upload-id", "addition-id", 7L, 2L, 1, 1, true);
        AdditionalAttachmentUploadItem item = new AdditionalAttachmentUploadItem(batch, 1, "photo.jpg", "image/jpeg", 33L, "new-photo");
        when(transactions.loadForVerification(7L, 2L, "upload-id"))
                .thenReturn(new AdditionalAttachmentUploadTransactionService.VerificationBatch(
                        "addition-id", AdditionalAttachmentUploadStatus.PENDING, List.of(item), true, AttachmentConversionRoute.DB));
        RuntimeException failure = stage.equals("unsupported")
                ? new UnsupportedAttachmentFormatException() : new InvalidAttachmentUploadException();
        if (stage.equals("size")) {
            doThrow(failure).when(validator).verifySize("new-photo", 33L);
        } else {
            doThrow(failure).when(validator).verifyType("new-photo", "image/jpeg");
        }

        assertThatThrownBy(() -> service.verifyBatch(7L, 2L,
                new AdditionalAttachmentUploadCompleteRequest("upload-id"))).isSameAs(failure);
        verify(transactions).failExecution("addition-id", stage.equals("unsupported")
                ? "UNSUPPORTED_ATTACHMENT_FORMAT" : "INVALID_ATTACHMENT_UPLOAD");
        verify(transactions, never()).markVerified(any(), any(), any());
    }

    @Test
    void 소유권_조회_실패시_실행을_종료하지_않는다() {
        AdditionalAttachmentUploadTransactionService transactions = mock(AdditionalAttachmentUploadTransactionService.class);
        AttachmentUploadedFileValidator validator = mock(AttachmentUploadedFileValidator.class);
        AdditionalAttachmentUploadCompletionService service = new AdditionalAttachmentUploadCompletionService(transactions, validator,
                mock(AttachmentDerivativePreparationService.class),
                mock(TripAttachmentStorageClient.class),
                mock(AdditionalAttachmentAnalysisPreparationService.class), coordinator());
        InvalidAttachmentUploadException failure = new InvalidAttachmentUploadException();
        when(transactions.loadForVerification(7L, 2L, "foreign-upload" )).thenThrow(failure);

        assertThatThrownBy(() -> service.verifyBatch(7L, 2L,
                new AdditionalAttachmentUploadCompleteRequest("foreign-upload"))).isSameAs(failure);
        verify(transactions, never()).failExecution(any(), any());
        verifyNoInteractions(validator);
    }

    private TripMutationCoordinator coordinator() {
        TripMutationCoordinator coordinator = mock(TripMutationCoordinator.class);
        when(coordinator.execute(anyLong(), any())).thenAnswer(call -> {
            Supplier<?> operation = call.getArgument(1);
            return operation.get();
        });
        return coordinator;
    }
}
