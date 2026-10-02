package com.yeodam.yeodambe.trip.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yeodam.yeodambe.common.exception.*;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.integration.service.request.TripPhotoAnalysisRequest;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.common.exception.AttachmentStorageException;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private final TripPlaceNameService placeNames = mock(TripPlaceNameService.class);
    private final InitialUploadExecutionRegistry executions = mock(InitialUploadExecutionRegistry.class);
    private final TripProcessingStatusService statuses = mock(TripProcessingStatusService.class);
    private TripAttachmentService service;

    @BeforeEach
    void setUp() {
        service = new TripAttachmentService(trips, regions, storage, transactions,
                derivatives, analysis, placeNames, results, executions, statuses);
        when(placeNames.resolve(anyLong(), anyString(), any())).thenReturn(Map.of());
        when(trips.existsByIdAndUserIdAndDeletedAtIsNullAndProcessingStatus(
                7L, 1L, ProcessingStatus.PROCESSING)).thenReturn(true);
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
    void 예약_성공_후_여행_생성_시작_이벤트를_남긴다() {
        when(trips.findById(7L)).thenReturn(Optional.of(trip(1L)));
        when(transactions.reserve(7L, 1L)).thenReturn(reservation());
        when(storage.store(eq("run"), any())).thenThrow(new IllegalStateException("S3"));

        Logger logger = (Logger) LoggerFactory.getLogger(TripAttachmentService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThrows(IllegalStateException.class,
                    () -> service.uploadInitialAttachments(7L, 1L, List.of(jpeg(), jpeg())));

            ILoggingEvent event = appender.list.stream()
                    .filter(logEvent -> "여행 생성 사진 처리를 시작했습니다."
                            .equals(logEvent.getFormattedMessage()))
                    .findFirst()
                    .orElseThrow();

            assertEquals("trip_creation", keyValue(event, "event"));
            assertEquals("started", keyValue(event, "result"));
            assertEquals(7L, keyValue(event, "trip_id"));
            assertEquals("run", keyValue(event, "job_id"));
            assertEquals(2, keyValue(event, "expected_count"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
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
        when(derivatives.createAll(eq("run"), eq(List.of("first")), eq(List.of("image/jpeg")), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
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
        MockMultipartFile file = heic();
        StoredFile original = StoredFile.uploaded(1L, "photo.heic", "original", "image/heic");
        ReflectionTestUtils.setField(original, "id", 20L);
        TripAttachment attachment = TripAttachment.initial(
                7L, 20L, "analyze", "preview", "display");
        ReflectionTestUtils.setField(attachment, "id", 30L);
        DerivedPhotoKeys keys = new DerivedPhotoKeys(
                "original", "analyze", "preview", "display", null, null, null, null);
        TripRegion region = new TripRegion(trip, "50110", "제주특별자치도 제주시",
                new BigDecimal("33.5"), new BigDecimal("126.5"));
        var aiResult = mock(tools.jackson.databind.JsonNode.class);

        when(trips.findById(7L)).thenReturn(Optional.of(trip));
        when(transactions.reserve(7L, 1L)).thenReturn(reservation());
        when(storage.store("run", file)).thenReturn("original");
        when(derivatives.createAll(eq("run"), eq(List.of("original")), eq(List.of("image/heic")), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(keys)));
        when(transactions.saveFilesAndAttachments(7L, 1L, "run", List.of(file),
                List.of("original"), List.of("image/heic"), List.of(keys)))
                .thenReturn(new TripAttachmentTransactionService.SavedAttachments(
                        List.of(original), List.of(attachment)));
        when(regions.findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(7L)).thenReturn(List.of(region));
        when(executions.markAnalysisStarted(7L, "run")).thenReturn(true);
        when(analysis.analyze(eq(7L), eq("run"), any(), any())).thenAnswer(invocation -> {
            invocation.<BooleanSupplier>getArgument(3).getAsBoolean();
            return aiResult;
        });
        when(statuses.findStatus(7L, 1L)).thenReturn(new TripProcessingStatusResponse(
                7L,
                TripProcessingStatusResponse.Status.COMPLETED,
                new TripProcessingStatusResponse.Progress(1, 1),
                null,
                new TripProcessingStatusResponse.Result(7L, 1, 1, 0),
                null
        ));

        var response = service.uploadInitialAttachments(7L, 1L, List.of(file));

        assertEquals(TripProcessingStatusResponse.Status.COMPLETED, response.status());
        assertEquals(1, response.result().placeFolderCount());
        verify(storage).retain(List.of("original", "analyze", "preview", "display"));
        verify(placeNames).resolve(7L, "run", aiResult);
        verify(results).saveCompleted(7L, 1L, "run", List.of(attachment), aiResult, Map.of());
        verify(executions).markAnalysisStarted(7L, "run");
        verify(executions).release(7L, "run");
    }

    @Test
    void HEIC_표시본_키가_없으면_DB저장과_AI호출_전에_실패한다() {
        MockMultipartFile file = heic();
        DerivedPhotoKeys keys = new DerivedPhotoKeys(
                "original", "analyze", "preview", null, null, null, null, null);
        when(trips.findById(7L)).thenReturn(Optional.of(trip(1L)));
        when(transactions.reserve(7L, 1L)).thenReturn(reservation());
        when(storage.store("run", file)).thenReturn("original");
        when(derivatives.createAll(eq("run"), eq(List.of("original")), eq(List.of("image/heic")), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(keys)));

        assertThrows(IllegalStateException.class,
                () -> service.uploadInitialAttachments(7L, 1L, List.of(file)));

        verify(transactions, never()).saveFilesAndAttachments(
                anyLong(), anyLong(), anyString(), anyList(), anyList(), anyList(), anyList());
        verifyNoInteractions(analysis, results);
    }

    @Test
    void 첨부_저장_후_취소되면_AI를_호출하지_않고_요청_객체를_정리한다() {
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

        when(trips.findById(7L)).thenReturn(Optional.of(trip));
        when(transactions.reserve(7L, 1L)).thenReturn(reservation());
        when(storage.store("run", file)).thenReturn("original");
        when(derivatives.createAll(eq("run"), eq(List.of("original")), eq(List.of("image/jpeg")), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(keys)));
        when(transactions.saveFilesAndAttachments(7L, 1L, "run", List.of(file),
                List.of("original"), List.of("image/jpeg"), List.of(keys)))
                .thenReturn(new TripAttachmentTransactionService.SavedAttachments(
                        List.of(original), List.of(attachment)));
        when(regions.findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(7L)).thenReturn(List.of(region));
        when(trips.existsByIdAndUserIdAndDeletedAtIsNullAndProcessingStatus(
                7L, 1L, ProcessingStatus.PROCESSING)).thenReturn(false);

        assertThrows(TripInitialAttachmentUploadNotAllowedException.class,
                () -> service.uploadInitialAttachments(7L, 1L, List.of(file)));

        verifyNoInteractions(analysis);
        verify(storage).delete("original");
        verify(storage).delete("analyze");
        verify(storage).delete("preview");
    }

    @Test
    void 완료_저장_후_응답_조회가_실패해도_완료된_첨부를_정리하지_않는다() {
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
        when(derivatives.createAll(eq("run"), eq(List.of("original")), eq(List.of("image/jpeg")), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(keys)));
        when(transactions.saveFilesAndAttachments(7L, 1L, "run", List.of(file),
                List.of("original"), List.of("image/jpeg"), List.of(keys)))
                .thenReturn(new TripAttachmentTransactionService.SavedAttachments(
                        List.of(original), List.of(attachment)));
        when(regions.findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(7L)).thenReturn(List.of(region));
        when(analysis.analyze(eq(7L), eq("run"), any(), any())).thenReturn(aiResult);
        when(statuses.findStatus(7L, 1L)).thenThrow(new IllegalStateException("집계 실패"));

        assertThrows(IllegalStateException.class,
                () -> service.uploadInitialAttachments(7L, 1L, List.of(file)));

        verify(results).saveCompleted(7L, 1L, "run", List.of(attachment), aiResult, Map.of());
        verify(transactions, never()).failAndDeleteReference(anyLong(), anyLong(), anyString(), anyList(), anyList());
        verify(storage, never()).delete(anyString());
        verify(executions).release(7L, "run");
    }

    private Object keyValue(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> key.equals(pair.key))
                .map(pair -> pair.value)
                .findFirst()
                .orElse(null);
    }

    @Test
    void AI가_명시적으로_실패하면_정리한_뒤_FAILED_응답을_반환한다() {
        Trip trip = trip(1L);
        MockMultipartFile file = jpeg();
        StoredFile original = StoredFile.uploaded(1L, "photo.jpg", "original", "image/jpeg");
        ReflectionTestUtils.setField(original, "id", 20L);
        TripAttachment attachment = TripAttachment.initial(7L, 20L, "analyze", "preview");
        ReflectionTestUtils.setField(attachment, "id", 30L);
        DerivedPhotoKeys keys = new DerivedPhotoKeys(
                "original", "analyze", "preview", "display", null, null, null, null);
        TripRegion region = new TripRegion(trip, "50110", "제주특별자치도 제주시",
                new BigDecimal("33.5"), new BigDecimal("126.5"));

        when(trips.findById(7L)).thenReturn(Optional.of(trip));
        when(transactions.reserve(7L, 1L)).thenReturn(reservation());
        when(storage.store("run", file)).thenReturn("original");
        when(derivatives.createAll(eq("run"), eq(List.of("original")), eq(List.of("image/jpeg")), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(keys)));
        when(transactions.saveFilesAndAttachments(7L, 1L, "run", List.of(file),
                List.of("original"), List.of("image/jpeg"), List.of(keys)))
                .thenReturn(new TripAttachmentTransactionService.SavedAttachments(
                        List.of(original), List.of(attachment)));
        when(regions.findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(7L)).thenReturn(List.of(region));
        when(analysis.analyze(eq(7L), eq("run"), any(), any())).thenThrow(
                new AiProcessingFailedException(
                        7L, 12, 128, null,
                        "AI_PROCESSING_FAILED", "첨부 처리에 실패했습니다."));

        TripProcessingStatusResponse response =
                service.uploadInitialAttachments(7L, 1L, List.of(file));

        assertEquals(TripProcessingStatusResponse.Status.FAILED, response.status());
        assertEquals(12, response.progress().done());
        assertEquals(128, response.progress().total());
        assertEquals("AI_PROCESSING_FAILED", response.error().code());
        verify(transactions).failAndDeleteReference(7L, 1L, "run", List.of(20L), List.of(30L));
        verify(storage).delete("original");
        verify(storage).delete("analyze");
        verify(storage).delete("preview");
        verify(storage).delete("display");
        verify(executions).release(7L, "run");
        verifyNoInteractions(statuses);
    }

    @Test
    void 중간_배치는_저장만_완료하고_AI를_호출하지_않는다() {
        InitialUploadExecutionRegistry registry = new InitialUploadExecutionRegistry();
        TripAttachmentService batchService = new TripAttachmentService(
                trips, regions, storage, transactions, derivatives, analysis, placeNames, results, registry, statuses);
        MockMultipartFile file = jpeg();
        StoredFile original = original(20L, "original-1");
        TripAttachment attachment = attachment(30L, 20L, "analyze-1", "preview-1");
        DerivedPhotoKeys keys = new DerivedPhotoKeys("original-1", "analyze-1", "preview-1");
        when(trips.findById(7L)).thenReturn(Optional.of(trip(1L)));
        when(transactions.reserveBatch(7L, 1L, 1, 2)).thenAnswer(invocation -> {
            var reserved = registry.reserveBatch(7L, 1, 2);
            return new TripAttachmentTransactionService.Reservation(reserved.executionId(), List.of());
        });
        when(storage.store(anyString(), eq(file))).thenReturn("original-1");
        when(derivatives.createAll(anyString(), eq(List.of("original-1")), eq(List.of("image/jpeg")), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(keys)));
        when(transactions.saveFilesAndAttachments(eq(7L), eq(1L), anyString(), eq(List.of(file)),
                eq(List.of("original-1")), eq(List.of("image/jpeg")), eq(List.of(keys))))
                .thenReturn(new TripAttachmentTransactionService.SavedAttachments(
                        List.of(original), List.of(attachment)));

        var response = batchService.uploadInitialAttachments(7L, 1L, List.of(file), 1, 2, false);

        assertTrue(response.isEmpty());
        verifyNoInteractions(analysis, results);
        verify(storage).retain(List.of("original-1", "analyze-1", "preview-1"));
        assertEquals(InitialUploadExecutionRegistry.State.UPLOADING, registry.snapshot(7L).state());
    }

    @Test
    void 후속_배치_접수_거부는_이전_성공_배치를_보존한다() {
        InitialUploadExecutionRegistry registry = new InitialUploadExecutionRegistry();
        TripAttachmentService batchService = new TripAttachmentService(
                trips, regions, storage, transactions, derivatives, analysis, placeNames, results, registry, statuses);
        MockMultipartFile file = jpeg();
        StoredFile original = original(20L, "original-1");
        TripAttachment attachment = attachment(30L, 20L, "analyze-1", "preview-1");
        DerivedPhotoKeys keys = new DerivedPhotoKeys("original-1", "analyze-1", "preview-1");
        when(trips.findById(7L)).thenReturn(Optional.of(trip(1L)));
        when(transactions.reserveBatch(7L, 1L, 1, 2)).thenAnswer(invocation -> {
            var reserved = registry.reserveBatch(7L, 1, 2);
            return new TripAttachmentTransactionService.Reservation(reserved.executionId(), List.of());
        });
        when(storage.store(anyString(), eq(file))).thenReturn("original-1");
        when(derivatives.createAll(anyString(), eq(List.of("original-1")), eq(List.of("image/jpeg")), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(keys)));
        when(transactions.saveFilesAndAttachments(eq(7L), eq(1L), anyString(), eq(List.of(file)),
                eq(List.of("original-1")), eq(List.of("image/jpeg")), eq(List.of(keys))))
                .thenReturn(new TripAttachmentTransactionService.SavedAttachments(
                        List.of(original), List.of(attachment)));

        var response = batchService.uploadInitialAttachments(7L, 1L, List.of(file), 1, 2, false);

        assertTrue(response.isEmpty());
        verifyNoInteractions(analysis, results);
        verify(storage).retain(List.of("original-1", "analyze-1", "preview-1"));
        assertEquals(InitialUploadExecutionRegistry.State.UPLOADING, registry.snapshot(7L).state());
        when(transactions.reserveBatch(7L, 1L, 2, 2)).thenAnswer(invocation -> {
            var reserved = registry.reserveBatch(7L, 2, 2);
            return new TripAttachmentTransactionService.Reservation(reserved.executionId(), List.of());
        });
        MockMultipartFile next = jpeg("next.jpg");
        when(storage.store(anyString(), eq(next))).thenReturn("original-2");
        when(derivatives.createAll(anyString(), eq(List.of("original-2")), eq(List.of("image/jpeg")), any()))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("full"));
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> batchService.uploadInitialAttachments(7L, 1L, List.of(next), 2, 2, true));
        verify(storage).delete("original-2");
        verify(storage, never()).delete("original-1");
        verify(storage, never()).delete("analyze-1");
        verify(storage, never()).delete("preview-1");
        verifyNoInteractions(analysis, results);
        assertEquals(1, registry.snapshot(7L).attachmentCount());
        assertEquals(2, registry.snapshot(7L).nextBatchNo());
    }

    @Test
    void 마지막_배치는_모든_배치의_첨부로_AI를_한번_호출한다() {
        InitialUploadExecutionRegistry registry = new InitialUploadExecutionRegistry();
        TripAttachmentService batchService = new TripAttachmentService(
                trips, regions, storage, transactions, derivatives, analysis, placeNames, results, registry, statuses);
        Trip trip = trip(1L);
        MockMultipartFile firstFile = jpeg("first.jpg");
        MockMultipartFile secondFile = jpeg("second.jpg");
        StoredFile firstOriginal = original(20L, "original-1");
        StoredFile secondOriginal = original(21L, "original-2");
        TripAttachment firstAttachment = attachment(30L, 20L, "analyze-1", "preview-1");
        TripAttachment secondAttachment = attachment(31L, 21L, "analyze-2", "preview-2");
        DerivedPhotoKeys firstKeys = new DerivedPhotoKeys("original-1", "analyze-1", "preview-1");
        DerivedPhotoKeys secondKeys = new DerivedPhotoKeys("original-2", "analyze-2", "preview-2");
        TripRegion region = new TripRegion(trip, "50110", "제주특별자치도 제주시",
                new BigDecimal("33.5"), new BigDecimal("126.5"));
        var aiResult = mock(tools.jackson.databind.JsonNode.class);

        when(trips.findById(7L)).thenReturn(Optional.of(trip));
        when(transactions.reserveBatch(eq(7L), eq(1L), anyInt(), eq(2))).thenAnswer(invocation -> {
            int batchNo = invocation.getArgument(2);
            var reserved = registry.reserveBatch(7L, batchNo, 2);
            return new TripAttachmentTransactionService.Reservation(reserved.executionId(), List.of());
        });
        when(storage.store(anyString(), eq(firstFile))).thenReturn("original-1");
        when(storage.store(anyString(), eq(secondFile))).thenReturn("original-2");
        when(derivatives.createAll(anyString(), eq(List.of("original-1")), eq(List.of("image/jpeg")), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(firstKeys)));
        when(derivatives.createAll(anyString(), eq(List.of("original-2")), eq(List.of("image/jpeg")), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(secondKeys)));
        when(transactions.saveFilesAndAttachments(eq(7L), eq(1L), anyString(), eq(List.of(firstFile)),
                eq(List.of("original-1")), eq(List.of("image/jpeg")), eq(List.of(firstKeys))))
                .thenReturn(new TripAttachmentTransactionService.SavedAttachments(
                        List.of(firstOriginal), List.of(firstAttachment)));
        when(transactions.saveFilesAndAttachments(eq(7L), eq(1L), anyString(), eq(List.of(secondFile)),
                eq(List.of("original-2")), eq(List.of("image/jpeg")), eq(List.of(secondKeys))))
                .thenReturn(new TripAttachmentTransactionService.SavedAttachments(
                        List.of(secondOriginal), List.of(secondAttachment)));
        when(regions.findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(7L)).thenReturn(List.of(region));
        when(analysis.analyze(eq(7L), anyString(), any(), any())).thenReturn(aiResult);
        when(statuses.findStatus(7L, 1L)).thenReturn(new TripProcessingStatusResponse(
                7L, TripProcessingStatusResponse.Status.COMPLETED,
                new TripProcessingStatusResponse.Progress(2, 2), null,
                new TripProcessingStatusResponse.Result(7L, 1, 2, 0), null));

        Logger logger = (Logger) LoggerFactory.getLogger(TripAttachmentService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertTrue(batchService.uploadInitialAttachments(
                    7L, 1L, List.of(firstFile), 1, 2, false).isEmpty());
            var response = batchService.uploadInitialAttachments(
                    7L, 1L, List.of(secondFile), 2, 2, true);

            assertEquals(TripProcessingStatusResponse.Status.COMPLETED, response.orElseThrow().status());
            var request = org.mockito.ArgumentCaptor.forClass(TripPhotoAnalysisRequest.class);
            verify(analysis, times(1)).analyze(eq(7L), anyString(), request.capture(), any());
            assertEquals(List.of(30L, 31L), request.getValue().attachments().stream()
                    .map(TripPhotoAnalysisRequest.Photo::tripAttachmentId).toList());
            verify(results).saveCompleted(eq(7L), eq(1L), anyString(),
                    eq(List.of(firstAttachment, secondAttachment)), eq(aiResult), eq(Map.of()));
            verify(placeNames).resolve(eq(7L), anyString(), eq(aiResult));

            ILoggingEvent started = appender.list.stream()
                    .filter(event -> "trip_creation".equals(keyValue(event, "event")))
                    .filter(event -> "started".equals(keyValue(event, "result")))
                    .findFirst()
                    .orElseThrow();
            ILoggingEvent success = appender.list.stream()
                    .filter(event -> "trip_creation".equals(keyValue(event, "event")))
                    .filter(event -> "success".equals(keyValue(event, "result")))
                    .findFirst()
                    .orElseThrow();

            assertEquals(2, keyValue(started, "expected_count"));
            assertEquals(2, keyValue(success, "saved_count"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void 배치_파일_합계가_145MiB를_넘으면_413으로_거부한다() {
        when(trips.findById(7L)).thenReturn(Optional.of(trip(1L)));
        var first = mock(org.springframework.web.multipart.MultipartFile.class);
        var second = mock(org.springframework.web.multipart.MultipartFile.class);
        when(first.getSize()).thenReturn(100L * 1024 * 1024);
        when(second.getSize()).thenReturn(45L * 1024 * 1024 + 1);

        assertThrows(AttachmentUploadLimitExceededException.class,
                () -> service.uploadInitialAttachments(
                        7L, 1L, List.of(first, second), 1, 2, true));
        verifyNoInteractions(storage, transactions);
    }

    @Test
    void 접수_거부는_원본과_예약을_정리하고_AI를_호출하지_않는다() {
        when(trips.findById(7L)).thenReturn(Optional.of(trip(1L)));
        when(transactions.reserve(7L, 1L)).thenReturn(reservation());
        when(storage.store(eq("run"), any())).thenReturn("rejected-original");
        when(derivatives.createAll(eq("run"), eq(List.of("rejected-original")), eq(List.of("image/jpeg")), any()))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("full"));
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> service.uploadInitialAttachments(7L, 1L, List.of(jpeg())));
        verify(storage).delete("rejected-original");
        verify(executions).release(7L, "run");
        verifyNoInteractions(analysis, results);
    }

    @Test
    void 변환에_현재_실행_검사를_전달하고_취소_실패는_후속_AI로_전달하지_않는다() {
        when(trips.findById(7L)).thenReturn(Optional.of(trip(1L)));
        when(transactions.reserve(7L, 1L)).thenReturn(reservation());
        when(storage.store(eq("run"), any())).thenReturn("original");
        when(executions.isCurrent(7L, "run")).thenReturn(true, false);
        when(derivatives.createAll(eq("run"), eq(List.of("original")), eq(List.of("image/jpeg")), any()))
                .thenAnswer(invocation -> {
                    java.util.function.BooleanSupplier active = invocation.getArgument(3);
                    assertTrue(active.getAsBoolean());
                    org.junit.jupiter.api.Assertions.assertFalse(active.getAsBoolean());
                    return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("canceled"));
                });
        assertThrows(RuntimeException.class, () -> service.uploadInitialAttachments(7L, 1L, List.of(jpeg())));
        verify(storage).delete("original");
        verifyNoInteractions(analysis, results);
    }

    private Trip trip(Long owner) {
        Trip trip = new Trip(owner, "여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "id", 7L);
        return trip;
    }

    private MockMultipartFile jpeg() {
        return jpeg("photo.jpg");
    }

    private MockMultipartFile jpeg(String name) {
        return new MockMultipartFile("attachments[]", name, "image/jpeg",
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9});
    }

    private MockMultipartFile heic() {
        return new MockMultipartFile("attachments[]", "photo.heic", "image/heic",
                new byte[]{0, 0, 0, 12, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'});
    }

    private StoredFile original(Long id, String key) {
        StoredFile file = StoredFile.uploaded(1L, "photo.jpg", key, "image/jpeg");
        ReflectionTestUtils.setField(file, "id", id);
        return file;
    }

    private TripAttachment attachment(Long id, Long fileId, String analyzeKey, String previewKey) {
        TripAttachment attachment = TripAttachment.initial(7L, fileId, analyzeKey, previewKey);
        ReflectionTestUtils.setField(attachment, "id", id);
        return attachment;
    }

    private TripAttachmentTransactionService.Reservation reservation() {
        return new TripAttachmentTransactionService.Reservation("run", List.of());
    }

}
