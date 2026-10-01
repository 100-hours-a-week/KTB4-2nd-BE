package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.UnsupportedAttachmentFormatException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadStatus;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.List;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class InitialAttachmentUploadCompletionServiceTest {
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final TripAttachmentDerivativeService derivatives = mock(TripAttachmentDerivativeService.class);
    private final InitialAttachmentUploadCompletionService service =
            new InitialAttachmentUploadCompletionService(storage, derivatives);
    private final InitialAttachmentUploadBatch batch = new InitialAttachmentUploadBatch(
            "upload-id", "execution-id", 7L, 42L, 1, 2, true);

    @Test
    void checksEveryStoredKeyAndAcceptsMatchingActualSizesWithoutChangingState() {
        when(storage.size("key-one")).thenReturn(1024L);
        when(storage.size("key-two")).thenReturn(2048L);
        when(storage.open("key-one")).thenReturn(new ByteArrayInputStream(jpeg(1024)));
        when(storage.open("key-two")).thenReturn(new ByteArrayInputStream(jpeg(2048)));

        assertThatCode(() -> service.verifyUploadedFiles(List.of(
                item(1, "key-one", 1024), item(2, "key-two", 2048))))
                .doesNotThrowAnyException();

        var order = inOrder(storage);
        order.verify(storage).size("key-one");
        order.verify(storage).open("key-one");
        order.verify(storage).size("key-two");
        order.verify(storage).open("key-two");
        verifyNoMoreInteractions(storage);
        assertThat(batch.getStatus()).isEqualTo(InitialAttachmentUploadStatus.PENDING);
    }

    @Test
    void rejectsSizeMismatchAndStopsBeforeCheckingRemainingFiles() {
        when(storage.size("key-one")).thenReturn(2048L);

        assertThatThrownBy(() -> service.verifyUploadedFiles(List.of(
                item(1, "key-one", 1024), item(2, "key-two", 2048))))
                .isInstanceOf(InvalidAttachmentUploadException.class);

        verify(storage).size("key-one");
        verifyNoMoreInteractions(storage);
    }

    @Test
    void rejectsMissingObjectReportedAs404() {
        when(storage.size("key-one")).thenThrow(s3Failure(404, null));

        assertThatThrownBy(() -> service.verifyUploadedFiles(List.of(item(1, "key-one", 1024))))
                .isInstanceOf(InvalidAttachmentUploadException.class);
    }

    @Test
    void preserves403AsStorageFailureRatherThanClaimingObjectIsMissing() {
        S3Exception failure = s3Failure(403, null);
        when(storage.size("key-one")).thenThrow(failure);

        assertStorageFailure(failure);
    }

    @Test
    void preservesS3ServerErrorAndStoredKey() {
        S3Exception failure = s3Failure(500, null);
        when(storage.size("key-one")).thenThrow(failure);

        assertStorageFailure(failure);
    }

    @Test
    void missingBucketIsStorageFailureEvenWhenStatusIs404() {
        S3Exception failure = s3Failure(404, "NoSuchBucket");
        when(storage.size("key-one")).thenThrow(failure);

        assertStorageFailure(failure);
    }

    @Test
    void acceptsPngAndAllHeicBrandsRecognizedByExistingUploadFlow() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        when(storage.size("png-key")).thenReturn((long) png.length);
        when(storage.open("png-key")).thenReturn(new ByteArrayInputStream(png));
        assertThatCode(() -> service.verifyUploadedFiles(List.of(
                new InitialAttachmentUploadItem(batch, 1, "photo.png", "image/png", (long) png.length, "png-key"))))
                .doesNotThrowAnyException();

        for (String brand : List.of("heic", "heix", "heim", "heis")) {
            byte[] header = new byte[12];
            System.arraycopy(("ftyp" + brand).getBytes(StandardCharsets.US_ASCII), 0, header, 4, 8);
            String key = "heic-" + brand;
            when(storage.size(key)).thenReturn(12L);
            when(storage.open(key)).thenReturn(new ByteArrayInputStream(header));
            assertThatCode(() -> service.verifyUploadedFiles(List.of(
                    new InitialAttachmentUploadItem(batch, 1, "photo.heic", "image/heic", 12L, key))))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsRecognizedTypeWhenItDiffersFromDeclaredType() {
        when(storage.size("key-one")).thenReturn(12L);
        when(storage.open("key-one")).thenReturn(new ByteArrayInputStream(jpeg(12)));

        assertThatThrownBy(() -> service.verifyUploadedFiles(List.of(
                new InitialAttachmentUploadItem(batch, 1, "photo.png", "image/png", 12L, "key-one"))))
                .isInstanceOf(InvalidAttachmentUploadException.class);
    }

    @Test
    void rejectsUnsupportedAndTruncatedHeaders() {
        List<byte[]> unsupported = List.of(
                "GIF89a".getBytes(StandardCharsets.US_ASCII),
                new byte[]{(byte) 0xff, (byte) 0xd8},
                new byte[]{(byte) 0x89, 'P', 'N', 'G'},
                "0000ftyphevc".getBytes(StandardCharsets.US_ASCII));

        for (byte[] bytes : unsupported) {
            when(storage.size("key-one")).thenReturn((long) bytes.length);
            when(storage.open("key-one")).thenReturn(new ByteArrayInputStream(bytes));
            assertThatThrownBy(() -> service.verifyUploadedFiles(List.of(item(1, "key-one", bytes.length))))
                    .isInstanceOf(UnsupportedAttachmentFormatException.class);
        }
    }

    @Test
    void closesStreamAfterReadingAtMostTwelveBytes() throws Exception {
        InputStream stream = mock(InputStream.class);
        when(stream.readNBytes(12)).thenReturn(jpeg(12));
        when(storage.size("key-one")).thenReturn(1024L);
        when(storage.open("key-one")).thenReturn(stream);

        service.verifyUploadedFiles(List.of(item(1, "key-one", 1024)));

        verify(stream).readNBytes(12);
        verify(stream).close();
        verifyNoMoreInteractions(stream);
    }

    @Test
    void preservesReadFailureAndClosesStream() throws Exception {
        InputStream stream = mock(InputStream.class);
        IOException failure = new IOException("test read failure");
        when(stream.readNBytes(12)).thenThrow(failure);
        when(storage.size("key-one")).thenReturn(1024L);
        when(storage.open("key-one")).thenReturn(stream);

        assertThatThrownBy(() -> service.verifyUploadedFiles(List.of(item(1, "key-one", 1024))))
                .isInstanceOfSatisfying(AttachmentStorageException.class, exception -> {
                    assertThat(exception.getObjectKey()).isEqualTo("key-one");
                    assertThat(exception.getCause()).isSameAs(failure);
                });
        verify(stream).close();
    }

    @Test
    void preservesGetObjectFailureAfterSuccessfulHeadCheck() {
        S3Exception failure = s3Failure(500, null);
        when(storage.size("key-one")).thenReturn(1024L);
        when(storage.open("key-one")).thenThrow(failure);

        assertStorageFailure(failure);
    }

    @Test
    void passesOrderedKeysAndTypesAndReturnsConversionResultsWithMetadata() {
        var takenAt = java.time.OffsetDateTime.parse("2026-10-01T10:00:00+09:00");
        List<DerivedPhotoKeys> results = List.of(
                new DerivedPhotoKeys("key-one", "analyze-one", "preview-one", takenAt,
                        java.math.BigDecimal.ONE, java.math.BigDecimal.TEN, "camera"),
                new DerivedPhotoKeys("key-two", "analyze-two", "preview-two"));
        when(derivatives.createAll("execution-id", List.of("key-one", "key-two"),
                List.of("image/jpeg", "image/jpeg")))
                .thenReturn(CompletableFuture.completedFuture(results));

        assertThat(service.createDerived("execution-id", List.of(
                item(1, "key-one", 1024), item(2, "key-two", 2048))))
                .isSameAs(results);
        assertThat(results.getFirst().takenAt()).isEqualTo(takenAt);
        verify(derivatives).createAll("execution-id", List.of("key-one", "key-two"),
                List.of("image/jpeg", "image/jpeg"));
        verifyNoMoreInteractions(storage, derivatives);
        assertThat(batch.getStatus()).isEqualTo(InitialAttachmentUploadStatus.PENDING);
    }

    @Test
    void rejectsMissingResultsOrIncorrectResultCount() {
        for (List<DerivedPhotoKeys> results : java.util.Arrays.<List<DerivedPhotoKeys>>asList(null, List.of())) {
            when(derivatives.createAll("execution-id", List.of("key-one"), List.of("image/jpeg")))
                    .thenReturn(CompletableFuture.completedFuture(results));

            assertThatThrownBy(() -> service.createDerived("execution-id", List.of(item(1, "key-one", 1024))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("파생 사진 수가 다릅니다.");
        }
    }

    @Test
    void rejectsNullResultWrongOriginalOrMissingRequiredDerivedKeys() {
        List<DerivedPhotoKeys> invalid = java.util.Arrays.asList(
                null,
                new DerivedPhotoKeys("other-key", "analyze", "preview"),
                new DerivedPhotoKeys("key-one", null, "preview"),
                new DerivedPhotoKeys("key-one", " ", "preview"),
                new DerivedPhotoKeys("key-one", "analyze", null),
                new DerivedPhotoKeys("key-one", "analyze", " "));
        for (DerivedPhotoKeys result : invalid) {
            when(derivatives.createAll("execution-id", List.of("key-one"), List.of("image/jpeg")))
                    .thenReturn(CompletableFuture.completedFuture(java.util.Collections.singletonList(result)));

            assertThatThrownBy(() -> service.createDerived("execution-id", List.of(item(1, "key-one", 1024))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("파생 사진 결과가 올바르지 않습니다.");
        }
    }

    @Test
    void requiresDisplayKeyForHeicAndAcceptsCompleteHeicResult() {
        var heic = new InitialAttachmentUploadItem(batch, 1, "photo.heic", "image/heic", 1024L, "key-one");
        for (String displayKey : java.util.Arrays.asList(null, " ", "display")) {
            List<DerivedPhotoKeys> results = List.of(new DerivedPhotoKeys(
                    "key-one", "analyze", "preview", displayKey, null, null, null, null));
            when(derivatives.createAll("execution-id", List.of("key-one"), List.of("image/heic")))
                    .thenReturn(CompletableFuture.completedFuture(results));

            if ("display".equals(displayKey)) {
                assertThat(service.createDerived("execution-id", List.of(heic))).isSameAs(results);
            } else {
                assertThatThrownBy(() -> service.createDerived("execution-id", List.of(heic)))
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test
    void propagatesAsynchronousConversionFailure() {
        IllegalStateException failure = new IllegalStateException("conversion failed");
        when(derivatives.createAll("execution-id", List.of("key-one"), List.of("image/jpeg")))
                .thenReturn(CompletableFuture.failedFuture(failure));

        assertThatThrownBy(() -> service.createDerived("execution-id", List.of(item(1, "key-one", 1024))))
                .isInstanceOf(CompletionException.class)
                .hasCause(failure);
        verifyNoMoreInteractions(storage);
    }

    @Test
    void retainsOriginalAndDerivedKeysOnceInOrderAndIncludesOnlyPresentDisplayKeys() {
        service.retainFiles(List.of(
                new DerivedPhotoKeys("original-one", "analyze-one", "preview-one"),
                new DerivedPhotoKeys("original-two", "analyze-two", "preview-two", "display-two",
                        null, null, null, null),
                new DerivedPhotoKeys("original-one", "analyze-one", "preview-one", " ",
                        null, null, null, null)));

        verify(storage).retain(List.of("original-one", "analyze-one", "preview-one",
                "original-two", "analyze-two", "preview-two", "display-two"));
        verifyNoMoreInteractions(storage, derivatives);
    }

    @Test
    void propagatesRetentionFailureWithoutCallingOtherStorageOperations() {
        S3Exception failure = s3Failure(403, null);
        org.mockito.Mockito.doThrow(failure).when(storage)
                .retain(List.of("original", "analyze", "preview"));

        assertThatThrownBy(() -> service.retainFiles(List.of(
                new DerivedPhotoKeys("original", "analyze", "preview"))))
                .isSameAs(failure);
        verify(storage).retain(List.of("original", "analyze", "preview"));
        verifyNoMoreInteractions(storage, derivatives);
    }

    private byte[] jpeg(int size) {
        byte[] bytes = new byte[size];
        bytes[0] = (byte) 0xff;
        bytes[1] = (byte) 0xd8;
        bytes[2] = (byte) 0xff;
        return bytes;
    }

    private void assertStorageFailure(S3Exception failure) {
        assertThatThrownBy(() -> service.verifyUploadedFiles(List.of(item(1, "key-one", 1024))))
                .isInstanceOfSatisfying(AttachmentStorageException.class, exception -> {
                    assertThat(exception.getObjectKey()).isEqualTo("key-one");
                    assertThat(exception.getCause()).isSameAs(failure);
                });
    }

    private S3Exception s3Failure(int statusCode, String errorCode) {
        S3Exception.Builder builder = S3Exception.builder();
        builder.statusCode(statusCode);
        if (errorCode != null) {
            builder.awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).build());
        }
        return (S3Exception) builder.build();
    }

    private InitialAttachmentUploadItem item(int order, String key, long bytes) {
        return new InitialAttachmentUploadItem(batch, order, "photo.jpg", "image/jpeg", bytes, key);
    }
}
