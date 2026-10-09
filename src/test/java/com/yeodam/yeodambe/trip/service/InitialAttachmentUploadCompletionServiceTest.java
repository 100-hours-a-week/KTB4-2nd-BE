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
            new InitialAttachmentUploadCompletionService(storage, derivatives,
                    mock(InitialAttachmentUploadTransactionService.class),
                    mock(com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadItemRepository.class),
                    mock(com.yeodam.yeodambe.trip.repository.TripRegionRepository.class),
                    mock(com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService.class),
                    mock(TripPlaceNameService.class), mock(TripAnalysisResultService.class),
                    mock(TripProcessingStatusService.class));
    private final InitialAttachmentUploadBatch batch = new InitialAttachmentUploadBatch(
            "upload-id", "execution-id", 7L, 42L, 1, 2, true);

    @Test
    void 모든_저장_키를_확인하고_실제_크기가_일치하면_상태_변경_없이_허용한다() {
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
    void 크기_불일치를_거부하고_남은_파일을_확인하기_전에_중단한다() {
        when(storage.size("key-one")).thenReturn(2048L);

        assertThatThrownBy(() -> service.verifyUploadedFiles(List.of(
                item(1, "key-one", 1024), item(2, "key-two", 2048))))
                .isInstanceOf(InvalidAttachmentUploadException.class);

        verify(storage).size("key-one");
        verifyNoMoreInteractions(storage);
    }

    @Test
    void 상태_404로_보고된_없는_객체를_거부한다() {
        when(storage.size("key-one")).thenThrow(s3Failure(404, null));

        assertThatThrownBy(() -> service.verifyUploadedFiles(List.of(item(1, "key-one", 1024))))
                .isInstanceOf(InvalidAttachmentUploadException.class);
    }

    @Test
    void 상태_403을_객체_누락으로_판단하지_않고_저장소_오류로_유지한다() {
        S3Exception failure = s3Failure(403, null);
        when(storage.size("key-one")).thenThrow(failure);

        assertStorageFailure(failure);
    }

    @Test
    void S3_서버_오류와_저장_키를_유지한다() {
        S3Exception failure = s3Failure(500, null);
        when(storage.size("key-one")).thenThrow(failure);

        assertStorageFailure(failure);
    }

    @Test
    void 버킷이_없으면_상태가_404여도_저장소_오류로_처리한다() {
        S3Exception failure = s3Failure(404, "NoSuchBucket");
        when(storage.size("key-one")).thenThrow(failure);

        assertStorageFailure(failure);
    }

    @Test
    void 기존_업로드가_인식하는_PNG와_모든_HEIC_브랜드를_허용한다() {
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
    void 인식한_파일_형식이_선언한_형식과_다르면_거부한다() {
        when(storage.size("key-one")).thenReturn(12L);
        when(storage.open("key-one")).thenReturn(new ByteArrayInputStream(jpeg(12)));

        assertThatThrownBy(() -> service.verifyUploadedFiles(List.of(
                new InitialAttachmentUploadItem(batch, 1, "photo.png", "image/png", 12L, "key-one"))))
                .isInstanceOf(InvalidAttachmentUploadException.class);
    }

    @Test
    void 지원하지_않거나_잘린_헤더를_거부한다() {
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
    void 최대_12바이트를_읽은_후_스트림을_닫는다() throws Exception {
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
    void 읽기_오류를_유지하고_스트림을_닫는다() throws Exception {
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
    void HEAD_확인이_성공한_후의_객체_조회_오류를_유지한다() {
        S3Exception failure = s3Failure(500, null);
        when(storage.size("key-one")).thenReturn(1024L);
        when(storage.open("key-one")).thenThrow(failure);

        assertStorageFailure(failure);
    }

    @Test
    void 정렬된_키와_형식을_전달하고_메타데이터가_있는_변환_결과를_반환한다() {
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
    void 결과가_없거나_결과_개수가_틀리면_거부한다() {
        for (List<DerivedPhotoKeys> results : java.util.Arrays.<List<DerivedPhotoKeys>>asList(null, List.of())) {
            when(derivatives.createAll("execution-id", List.of("key-one"), List.of("image/jpeg")))
                    .thenReturn(CompletableFuture.completedFuture(results));

            assertThatThrownBy(() -> service.createDerived("execution-id", List.of(item(1, "key-one", 1024))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("파생 사진 수가 다릅니다.");
        }
    }

    @Test
    void null_결과와_잘못된_원본과_필수_파생_키_누락을_거부한다() {
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
    void HEIC에는_표시용_키가_필요하고_완전한_HEIC_결과를_허용한다() {
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
    void 비동기_변환_실패를_전파한다() {
        IllegalStateException failure = new IllegalStateException("conversion failed");
        when(derivatives.createAll("execution-id", List.of("key-one"), List.of("image/jpeg")))
                .thenReturn(CompletableFuture.failedFuture(failure));

        assertThatThrownBy(() -> service.createDerived("execution-id", List.of(item(1, "key-one", 1024))))
                .isInstanceOf(CompletionException.class)
                .hasCause(failure);
        verifyNoMoreInteractions(storage);
    }

    @Test
    void 원본과_파생_키를_순서대로_한_번씩_보존하고_존재하는_표시용_키만_포함한다() {
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
    void 다른_저장소_작업을_호출하지_않고_보존_실패를_전파한다() {
        S3Exception failure = s3Failure(403, null);
        org.mockito.Mockito.doThrow(failure).when(storage)
                .retain(List.of("original", "analyze", "preview"));

        assertThatThrownBy(() -> service.retainFiles(List.of(
                new DerivedPhotoKeys("original", "analyze", "preview"))))
                .isSameAs(failure);
        verify(storage).retain(List.of("original", "analyze", "preview"));
        verifyNoMoreInteractions(storage, derivatives);
    }

    @Test
    void 모든_원본_키를_제외하고_각_파생_키를_한_번씩_삭제한다() {
        RuntimeException failure = new IllegalStateException("save failed");

        boolean cleaned = service.cleanupDerived(List.of(
                new DerivedPhotoKeys("original-one", "analyze-one", "preview-one"),
                new DerivedPhotoKeys("original-two", "analyze-one", "original-one", "display-two",
                        null, null, null, null),
                new DerivedPhotoKeys("original-three", "original-two", "preview-three", " ",
                        null, null, null, null)), failure);

        assertThat(cleaned).isTrue();
        assertThat(failure.getSuppressed()).isEmpty();
        var order = inOrder(storage);
        order.verify(storage).delete("analyze-one");
        order.verify(storage).delete("preview-one");
        order.verify(storage).delete("display-two");
        order.verify(storage).delete("preview-three");
        verifyNoMoreInteractions(storage, derivatives);
    }

    @Test
    void 실패해도_정리를_계속하고_모든_정리_오류를_원래_예외에_유지한다() {
        RuntimeException failure = new IllegalStateException("save failed");
        S3Exception firstFailure = s3Failure(403, null);
        S3Exception secondFailure = s3Failure(500, null);
        org.mockito.Mockito.doThrow(firstFailure).when(storage).delete("analyze");
        org.mockito.Mockito.doThrow(secondFailure).when(storage).delete("display");

        boolean cleaned = service.cleanupDerived(List.of(new DerivedPhotoKeys(
                "original", "analyze", "preview", "display", null, null, null, null)), failure);

        assertThat(cleaned).isFalse();
        assertThat(failure).hasMessage("save failed");
        assertThat(failure.getSuppressed()).containsExactly(firstFailure, secondFailure);
        var order = inOrder(storage);
        order.verify(storage).delete("analyze");
        order.verify(storage).delete("preview");
        order.verify(storage).delete("display");
        verifyNoMoreInteractions(storage, derivatives);
    }

    @Test
    void 변환_결과가_비어있으면_저장소를_정리하지_않는다() {
        RuntimeException failure = new IllegalStateException("validation failed");

        assertThat(service.cleanupDerived(List.of(), failure)).isTrue();

        assertThat(failure.getSuppressed()).isEmpty();
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
