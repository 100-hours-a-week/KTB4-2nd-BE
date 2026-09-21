package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.*;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TripAttachmentServiceTest {
    private final TripRepository trips = mock(TripRepository.class);
    private final TripRegionRepository regions = mock(TripRegionRepository.class);
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final TripAttachmentTransactionService transactions = mock(TripAttachmentTransactionService.class);
    private final TripAttachmentDerivativeService derivatives = mock(TripAttachmentDerivativeService.class);
    private final TripPhotoAnalysisService analysis = mock(TripPhotoAnalysisService.class);
    private final TripAnalysisResultService results = mock(TripAnalysisResultService.class);
    private final InitialUploadExecutionRegistry executions = mock(InitialUploadExecutionRegistry.class);
    private TripAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new TripAttachmentService(trips, regions, storage, transactions,
                derivatives, analysis, results, executions);
    }

    @Test
    void 없는_여행과_다른_소유자는_404로_거부한다() {
        when(trips.findById(7L)).thenReturn(Optional.empty(), Optional.of(trip(2L)));
        assertThrows(TripNotFoundException.class, () -> service.uploadInitialAttachments(7L, 1L, List.of(jpeg())));
        assertThrows(TripNotFoundException.class, () -> service.uploadInitialAttachments(7L, 1L, List.of(jpeg())));
        verifyNoInteractions(storage, transactions, analysis);
    }

    @Test
    void 이미_예약된_여행은_409로_거부한다() {
        when(trips.findById(7L)).thenReturn(Optional.of(trip(1L)));
        when(transactions.reserve(7L, 1L))
                .thenThrow(new TripInitialAttachmentUploadNotAllowedException());
        assertThrows(TripInitialAttachmentUploadNotAllowedException.class,
                () -> service.uploadInitialAttachments(7L, 1L, List.of(jpeg())));
        verifyNoInteractions(storage);
    }

    @Test
    void 선언한_형식이_아닌_실제_바이트로_판정한다() {
        when(trips.findById(7L)).thenReturn(Optional.of(trip(1L)));
        MockMultipartFile fake = new MockMultipartFile("attachments[]", "photo.jpg", "image/jpeg", "GIF89a".getBytes());
        assertThrows(UnsupportedAttachmentFormatException.class,
                () -> service.uploadInitialAttachments(7L, 1L, List.of(fake)));
        verifyNoInteractions(storage, transactions);
    }

    @Test
    void 장당_한도_초과는_413으로_거부한다() {
        when(trips.findById(7L)).thenReturn(Optional.of(trip(1L)));
        var big = mock(org.springframework.web.multipart.MultipartFile.class);
        when(big.getSize()).thenReturn(15L * 1024 * 1024 + 1);
        assertThrows(AttachmentUploadLimitExceededException.class,
                () -> service.uploadInitialAttachments(7L, 1L, List.of(big)));
        verifyNoInteractions(storage, transactions);
    }

    @Test
    void 두번째_원본_저장이_실패하면_첫번째_객체를_삭제하고_실패를_기록한다() {
        when(trips.findById(7L)).thenReturn(Optional.of(trip(1L)));
        when(transactions.reserve(7L, 1L)).thenReturn(reservation());
        MockMultipartFile first = jpeg();
        MockMultipartFile second = jpeg();
        when(storage.store(eq("run"), any())).thenReturn("first").thenThrow(new IllegalStateException("S3"));
        assertThrows(IllegalStateException.class,
                () -> service.uploadInitialAttachments(7L, 1L, List.of(first, second)));
        verify(transactions).failAndDeleteReference(7L, 1L, "run", List.of(), List.of());
        verify(storage).delete("first");
        verify(executions).release(7L, "run");
        verifyNoInteractions(analysis, results);
    }

    @Test
    void 파생_사진이_누락되면_AI를_호출하지_않고_원본을_정리한다() {
        when(trips.findById(7L)).thenReturn(Optional.of(trip(1L)));
        when(transactions.reserve(7L, 1L)).thenReturn(reservation());
        when(storage.store(eq("run"), any())).thenReturn("first");
        when(derivatives.createAll("run", List.of("first"))).thenReturn(CompletableFuture.completedFuture(List.of()));
        assertThrows(IllegalStateException.class,
                () -> service.uploadInitialAttachments(7L, 1L, List.of(jpeg())));
        verify(storage).delete("first");
        verifyNoInteractions(analysis, results);
    }

    @Test
    void S3_삭제가_실패해도_원래_실패를_반환하고_메모리_예약을_해제한다() {
        when(trips.findById(7L)).thenReturn(Optional.of(trip(1L)));
        when(transactions.reserve(7L, 1L)).thenReturn(reservation());
        when(storage.store(eq("run"), any())).thenReturn("first").thenThrow(new IllegalStateException("S3"));
        doThrow(new IllegalStateException("delete")).when(storage).delete("first");

        assertThrows(IllegalStateException.class,
                () -> service.uploadInitialAttachments(7L, 1L, List.of(jpeg(), jpeg())));

        verify(executions).release(7L, "run");
    }

    @Test
    void 저장_결과가_불확실한_S3_객체도_재정리_대상에_포함한다() {
        when(trips.findById(7L)).thenReturn(Optional.of(trip(1L)));
        when(transactions.reserve(7L, 1L)).thenReturn(reservation());
        when(storage.store(eq("run"), any()))
                .thenThrow(new AttachmentStorageException("uncertain", new RuntimeException()));

        assertThrows(AttachmentStorageException.class,
                () -> service.uploadInitialAttachments(7L, 1L, List.of(jpeg())));

        verify(storage).delete("uncertain");
    }

    @Test
    void 성공하면_S3_객체를_보존하고_메모리_예약을_해제한다() {
        Trip trip = trip(1L);
        MockMultipartFile file = jpeg();
        StoredFile original = StoredFile.uploaded(1L, "photo.jpg", "original", "image/jpeg");
        ReflectionTestUtils.setField(original, "id", 20L);
        TripAttachment attachment = TripAttachment.initial(7L, 20L, "analyze", "preview");
        ReflectionTestUtils.setField(attachment, "id", 30L);
        DerivedPhotoKeys keys = new DerivedPhotoKeys(
                "original", "analyze", "preview", null, null, null, null);
        TripRegion region = new TripRegion(trip, "50110", "제주특별자치도 제주시",
                new BigDecimal("33.5"), new BigDecimal("126.5"));
        var aiResult = mock(tools.jackson.databind.JsonNode.class);

        when(trips.findById(7L)).thenReturn(Optional.of(trip));
        when(transactions.reserve(7L, 1L)).thenReturn(reservation());
        when(storage.store("run", file)).thenReturn("original");
        when(derivatives.createAll("run", List.of("original")))
                .thenReturn(CompletableFuture.completedFuture(List.of(keys)));
        when(transactions.saveFilesAndAttachments(7L, 1L, "run", List.of(file),
                List.of("original"), List.of("image/jpeg"), List.of(keys)))
                .thenReturn(new TripAttachmentTransactionService.SavedAttachments(
                        List.of(original), List.of(attachment)));
        when(regions.findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(7L)).thenReturn(List.of(region));
        when(analysis.analyze(eq(7L), eq("run"), any(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(3).run();
            return aiResult;
        });

        var response = service.uploadInitialAttachments(7L, 1L, List.of(file));

        assertEquals(ProcessingStatus.COMPLETED, response.status());
        verify(storage).retain(List.of("original", "analyze", "preview"));
        verify(results).saveCompleted(7L, 1L, "run", List.of(attachment), aiResult);
        verify(executions).markAnalysisStarted(7L, "run");
        verify(executions).release(7L, "run");
    }

    private Trip trip(Long owner) {
        Trip trip = new Trip(owner, "여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "id", 7L);
        return trip;
    }

    private MockMultipartFile jpeg() {
        return new MockMultipartFile("attachments[]", "photo.jpg", "image/jpeg",
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9});
    }

    private TripAttachmentTransactionService.Reservation reservation() {
        return new TripAttachmentTransactionService.Reservation("run", List.of());
    }

}
