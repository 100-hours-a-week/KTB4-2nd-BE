package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttachmentDerivativePreparationServiceTest {
    private final TripAttachmentDerivativeService derivatives = mock(TripAttachmentDerivativeService.class);
    private final AttachmentDerivativePreparationService service = new AttachmentDerivativePreparationService(derivatives);
    private final List<String> keys = List.of("original-one", "original-two");
    private final List<String> types = List.of("image/jpeg", "image/heic");

    @Test
    void passesInputsToExistingConverterAndPreservesOrderedResults() {
        List<DerivedPhotoKeys> photos = List.of(
                new DerivedPhotoKeys("original-one", "analyze-one", "preview-one"),
                new DerivedPhotoKeys("original-two", "analyze-two", "preview-two", "display-two",
                        null, null, null, null));
        when(derivatives.createAll("batch-id", keys, types)).thenReturn(CompletableFuture.completedFuture(photos));

        assertThat(service.create("batch-id", keys, types)).isSameAs(photos);
        verify(derivatives).createAll("batch-id", keys, types);
    }

    @Test
    void rejectsNullOrIncompleteResultCount() {
        for (List<DerivedPhotoKeys> photos : Arrays.<List<DerivedPhotoKeys>>asList(null, List.of())) {
            doReturn(CompletableFuture.completedFuture(photos)).when(derivatives).createAll("batch-id", keys, types);
            assertThatThrownBy(() -> service.create("batch-id", keys, types))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(TripInternalErrorMessage.DERIVED_ATTACHMENT_COUNT_MISMATCH.message());
        }
    }

    @Test
    void rejectsWrongOriginalMissingKeysAndHeicWithoutDisplay() {
        List<DerivedPhotoKeys> invalid = Arrays.asList(
                null,
                new DerivedPhotoKeys("wrong", "analyze", "preview"),
                new DerivedPhotoKeys("original-two", null, "preview"),
                new DerivedPhotoKeys("original-two", "analyze", " "),
                new DerivedPhotoKeys("original-two", "analyze", "preview"));
        for (DerivedPhotoKeys photo : invalid) {
            List<DerivedPhotoKeys> photos = Arrays.asList(
                    new DerivedPhotoKeys("original-one", "analyze-one", "preview-one"), photo);
            doReturn(CompletableFuture.completedFuture(photos)).when(derivatives).createAll("batch-id", keys, types);
            assertThatThrownBy(() -> service.create("batch-id", keys, types))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(TripInternalErrorMessage.DERIVED_ATTACHMENT_RESULT_INVALID.message());
        }
    }

    @Test
    void propagatesFailureFromAsynchronousConverter() {
        RuntimeException failure = new IllegalStateException("test conversion failure");
        when(derivatives.createAll("batch-id", keys, types)).thenReturn(CompletableFuture.failedFuture(failure));
        assertThatThrownBy(() -> service.create("batch-id", keys, types))
                .isInstanceOf(CompletionException.class).hasCause(failure);
    }
}
