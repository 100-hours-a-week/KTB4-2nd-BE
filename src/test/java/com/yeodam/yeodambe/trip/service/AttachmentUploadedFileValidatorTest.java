package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.UnsupportedAttachmentFormatException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttachmentUploadedFileValidatorTest {
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final AttachmentUploadedFileValidator validator = new AttachmentUploadedFileValidator(storage);

    @Test
    void acceptsMatchingSizeAndRejectsMismatch() {
        when(storage.size("photo")).thenReturn(1024L);
        assertThatCode(() -> validator.verifySize("photo", 1024L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.verifySize("photo", 2048L))
                .isInstanceOf(InvalidAttachmentUploadException.class);
    }

    @Test
    void distinguishesMissingObjectFromMissingBucketAndAccessDenied() {
        when(storage.size("photo")).thenThrow(s3Failure(404, "NoSuchKey"));
        assertThatThrownBy(() -> validator.verifySize("photo", 1024))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        for (S3Exception failure : new S3Exception[]{s3Failure(404, "NoSuchBucket"), s3Failure(403, "AccessDenied")}) {
            doThrow(failure).when(storage).size("photo");
            assertThatThrownBy(() -> validator.verifySize("photo", 1024))
                    .isInstanceOf(AttachmentStorageException.class).hasCause(failure);
        }
    }

    @Test
    void acceptsJpegPngAndSupportedHeicHeaders() {
        accepts(new byte[]{(byte) 255, (byte) 216, (byte) 255}, "image/jpeg");
        accepts(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}, "image/png");
        for (String brand : new String[]{"heic", "heix", "heim", "heis"}) {
            accepts(("0000ftyp" + brand).getBytes(StandardCharsets.US_ASCII), "image/heic");
        }
    }

    @Test
    void rejectsDeclaredTypeMismatchAndUnrecognizedOrShortHeader() {
        when(storage.open("photo")).thenReturn(new ByteArrayInputStream(
                new byte[]{(byte) 255, (byte) 216, (byte) 255}));
        assertThatThrownBy(() -> validator.verifyType("photo", "image/png"))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        for (byte[] header : new byte[][]{new byte[0], new byte[]{(byte) 255}, "GIF89a".getBytes(StandardCharsets.US_ASCII)}) {
            when(storage.open("photo")).thenReturn(new ByteArrayInputStream(header));
            assertThatThrownBy(() -> validator.verifyType("photo", "image/jpeg"))
                    .isInstanceOf(UnsupportedAttachmentFormatException.class);
        }
    }

    @Test
    void closesStreamWhenTypeCheckFails() throws IOException {
        InputStream input = spy(new ByteArrayInputStream(new byte[]{(byte) 255, (byte) 216, (byte) 255}));
        when(storage.open("photo")).thenReturn(input);
        assertThatThrownBy(() -> validator.verifyType("photo", "image/png"))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        verify(input).close();
    }

    @Test
    void wrapsReadFailureAndClosesStream() throws IOException {
        InputStream input = mock(InputStream.class);
        IOException failure = new IOException("test read failure");
        when(input.readNBytes(12)).thenThrow(failure);
        when(storage.open("photo")).thenReturn(input);
        assertThatThrownBy(() -> validator.verifyType("photo", "image/jpeg"))
                .isInstanceOf(AttachmentStorageException.class).hasCause(failure);
        verify(input).close();
    }

    private void accepts(byte[] header, String type) {
        when(storage.open("photo")).thenReturn(new ByteArrayInputStream(header));
        assertThatCode(() -> validator.verifyType("photo", type)).doesNotThrowAnyException();
    }

    private S3Exception s3Failure(int status, String code) {
        S3Exception.Builder builder = S3Exception.builder();
        builder.statusCode(status);
        builder.awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build());
        return (S3Exception) builder.build();
    }
}
