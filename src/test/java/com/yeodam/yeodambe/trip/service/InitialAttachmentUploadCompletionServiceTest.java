package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.UnsupportedAttachmentFormatException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadCompleteRequest;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadBatchSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
    private final InitialAttachmentUploadTransactionService transactions = mock(InitialAttachmentUploadTransactionService.class);
    private final InitialAttachmentUploadCompletionService service =
            new InitialAttachmentUploadCompletionService(storage, transactions);
    private final InitialAttachmentUploadBatch batch = new InitialAttachmentUploadBatch(
            "upload-id", "execution-id", 7L, 42L, 1, 2, true);

    @Test
    void 모든_저장_키를_확인하고_실제_크기가_일치하면_상태_변경_없이_허용한다() {
        when(storage.size("key-one")).thenReturn(1024L);
        when(storage.size("key-two")).thenReturn(2048L);
        when(storage.openHeader("key-one")).thenReturn(new ByteArrayInputStream(jpeg(1024)));
        when(storage.openHeader("key-two")).thenReturn(new ByteArrayInputStream(jpeg(2048)));

        assertThatCode(() -> service.verifyUploadedFiles(List.of(
                item(1, "key-one", 1024), item(2, "key-two", 2048))))
                .doesNotThrowAnyException();

        var order = inOrder(storage);
        order.verify(storage).size("key-one");
        order.verify(storage).openHeader("key-one");
        order.verify(storage).size("key-two");
        order.verify(storage).openHeader("key-two");
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
        when(storage.openHeader("png-key")).thenReturn(new ByteArrayInputStream(png));
        assertThatCode(() -> service.verifyUploadedFiles(List.of(
                new InitialAttachmentUploadBatchSnapshot.Photo(1L, "photo.png", "image/png", (long) png.length, "png-key", 0, null, null))))
                .doesNotThrowAnyException();

        for (String brand : List.of("heic", "heix", "heim", "heis")) {
            byte[] header = new byte[12];
            System.arraycopy(("ftyp" + brand).getBytes(StandardCharsets.US_ASCII), 0, header, 4, 8);
            String key = "heic-" + brand;
            when(storage.size(key)).thenReturn(12L);
            when(storage.openHeader(key)).thenReturn(new ByteArrayInputStream(header));
            assertThatCode(() -> service.verifyUploadedFiles(List.of(
                    new InitialAttachmentUploadBatchSnapshot.Photo(1L, "photo.heic", "image/heic", 12L, key, 0, null, null))))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void 인식한_파일_형식이_선언한_형식과_다르면_거부한다() {
        when(storage.size("key-one")).thenReturn(12L);
        when(storage.openHeader("key-one")).thenReturn(new ByteArrayInputStream(jpeg(12)));

        assertThatThrownBy(() -> service.verifyUploadedFiles(List.of(
                new InitialAttachmentUploadBatchSnapshot.Photo(1L, "photo.png", "image/png", 12L, "key-one", 0, null, null))))
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
            when(storage.openHeader("key-one")).thenReturn(new ByteArrayInputStream(bytes));
            assertThatThrownBy(() -> service.verifyUploadedFiles(List.of(item(1, "key-one", bytes.length))))
                    .isInstanceOf(UnsupportedAttachmentFormatException.class);
        }
    }

    @Test
    void 최대_12바이트를_읽은_후_스트림을_닫는다() throws Exception {
        InputStream stream = mock(InputStream.class);
        when(stream.readNBytes(12)).thenReturn(jpeg(12));
        when(storage.size("key-one")).thenReturn(1024L);
        when(storage.openHeader("key-one")).thenReturn(stream);

        service.verifyUploadedFiles(List.of(item(1, "key-one", 1024)));

        verify(stream).readNBytes(12);
        verify(stream).close();
        verifyNoMoreInteractions(stream);
        Mockito.verify(storage, Mockito.never()).open(Mockito.anyString());
    }

    @Test
    void 읽기_오류를_유지하고_스트림을_닫는다() throws Exception {
        InputStream stream = mock(InputStream.class);
        IOException failure = new IOException("test read failure");
        when(stream.readNBytes(12)).thenThrow(failure);
        when(storage.size("key-one")).thenReturn(1024L);
        when(storage.openHeader("key-one")).thenReturn(stream);

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
        when(storage.openHeader("key-one")).thenThrow(failure);

        assertStorageFailure(failure);
    }

    @Test
    void 이미_접수한_배치는_원본을_재검사하지_않는다() {
        var snapshot = new InitialAttachmentUploadBatchSnapshot(
                1L, "upload-id", "execution-id", 7L, 42L, 1, 1, true,
                InitialAttachmentUploadStatus.ACCEPTED, null, List.of());
        when(transactions.loadAcceptanceCandidate(7L, 42L, "upload-id")).thenReturn(snapshot);
        var status = new TripProcessingStatusResponse(
                7L, TripProcessingStatusResponse.Status.PROCESSING,
                null, null, null, null);
        when(transactions.startProcessing(7L, 42L, "upload-id")).thenReturn(status);

        assertThat(service.complete(7L, 42L,
                new InitialAttachmentUploadCompleteRequest("upload-id")))
                .isEqualTo(status);
        Mockito.verifyNoInteractions(storage);
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

    private S3Exception s3Failure(
            int statusCode,
            String errorCode
    ) {
        S3Exception.Builder builder = S3Exception.builder();
        builder.statusCode(statusCode);
        if (errorCode != null) {
            builder.awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).build());
        }
        return (S3Exception) builder.build();
    }

    private InitialAttachmentUploadBatchSnapshot.Photo item(
            int order,
            String key,
            long bytes
    ) {
        return new InitialAttachmentUploadBatchSnapshot.Photo((long) order, "photo.jpg", "image/jpeg", bytes, key, 0, null, null);
    }
}
