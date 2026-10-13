package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.common.exception.GlobalExceptionHandler;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentIssueException;
import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import com.yeodam.yeodambe.common.exception.InvalidRestoreRequestException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.controller.TripAttachmentController;
import com.yeodam.yeodambe.trip.entity.AttachmentIssue;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.service.BulkAttachmentDownloadService;
import com.yeodam.yeodambe.trip.service.AdditionalAttachmentUploadUrlService;
import com.yeodam.yeodambe.trip.service.InitialAttachmentUploadCompletionService;
import com.yeodam.yeodambe.trip.service.InitialAttachmentUploadUrlService;
import com.yeodam.yeodambe.trip.service.TripAttachmentDeletionService;
import com.yeodam.yeodambe.trip.service.TripAttachmentDetailService;
import com.yeodam.yeodambe.trip.service.TripAttachmentDownloadService;
import com.yeodam.yeodambe.trip.service.TripAttachmentListService;
import com.yeodam.yeodambe.trip.service.TripAttachmentRestoreService;
import com.yeodam.yeodambe.trip.service.UnclassifiedFolderListService;
import com.yeodam.yeodambe.trip.service.request.AttachmentRestoreRequest;
import com.yeodam.yeodambe.trip.service.request.BulkAttachmentRestoreRequest;
import com.yeodam.yeodambe.trip.service.response.AttachmentRestoreResponse;
import com.yeodam.yeodambe.trip.service.response.BulkAttachmentDownloadResponse;
import com.yeodam.yeodambe.trip.service.response.BulkAttachmentRestoreResponse;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentDetailResponse;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentDownloadResponse;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentListResponse;
import com.yeodam.yeodambe.trip.service.response.UnclassifiedAttachmentListResponse;
import com.yeodam.yeodambe.user.service.UserStatsService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TripAttachmentControllerTest {
    private final TripAttachmentListService listService = mock(TripAttachmentListService.class);
    private final TripAttachmentDetailService detailService = mock(TripAttachmentDetailService.class);
    private final TripAttachmentDeletionService deletionService = mock(TripAttachmentDeletionService.class);
    private final TripAttachmentDownloadService downloadService = mock(TripAttachmentDownloadService.class);
    private final BulkAttachmentDownloadService bulkDownloadService = mock(BulkAttachmentDownloadService.class);
    private final InitialAttachmentUploadUrlService uploadUrlService = mock(InitialAttachmentUploadUrlService.class);
    private final AdditionalAttachmentUploadUrlService additionalUploadUrlService = mock(AdditionalAttachmentUploadUrlService.class);
    private final UnclassifiedFolderListService unclassifiedFolderListService =
            mock(UnclassifiedFolderListService.class);
    private final TripAttachmentRestoreService restoreService =
            mock(TripAttachmentRestoreService.class);
    private final InitialAttachmentUploadCompletionService uploadCompletion =
            mock(InitialAttachmentUploadCompletionService.class);
    private final TripAttachmentController controller = new TripAttachmentController(
            listService,
            detailService,
            deletionService,
            downloadService,
            bulkDownloadService,
            uploadUrlService,
            uploadCompletion,
            additionalUploadUrlService,
            mock(com.yeodam.yeodambe.trip.service.AdditionalAttachmentUploadCompletionService.class),
            unclassifiedFolderListService,
            restoreService
    );

    @Test
    void 소유자의_미분류_사진_목록을_명세대로_반환한다() throws Exception {
        when(unclassifiedFolderListService.findAttachments(1L, 7L, "BLURRY", "cursor"))
                .thenReturn(new UnclassifiedAttachmentListResponse(
                        AttachmentIssue.BLURRY,
                        "흐릿한 첨부",
                        1L,
                        List.of(new UnclassifiedAttachmentListResponse.Item(
                                502L,
                                "https://example.test/preview",
                                AttachmentIssue.BLURRY,
                                31L
                        )),
                        false,
                        null
                ));

        MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .build()
                .perform(get("/trips/7/unclassified-folders/BLURRY/attachments")
                        .param("cursor", "cursor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("UNCLASSIFIED_ATTACHMENT_DETAIL_FOUND"))
                .andExpect(jsonPath("$.data.issue").value("BLURRY"))
                .andExpect(jsonPath("$.data.name").value("흐릿한 첨부"))
                .andExpect(jsonPath("$.data.attachmentCount").value(1))
                .andExpect(jsonPath("$.data.items[0].tripAttachmentId").value(502))
                .andExpect(jsonPath("$.data.items[0].thumbnailUrl").value("https://example.test/preview"))
                .andExpect(jsonPath("$.data.items[0].issue").value("BLURRY"))
                .andExpect(jsonPath("$.data.items[0].restoreTripPlaceId").value(31))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(Matchers.nullValue()));

        verify(unclassifiedFolderListService).findAttachments(1L, 7L, "BLURRY", "cursor");
    }

    @Test
    void 미분류_사진의_복구_장소가_null이어도_다음_페이지를_반환한다() throws Exception {
        when(unclassifiedFolderListService.findAttachments(1L, 7L, "UNCLEAR_LOCATION", null))
                .thenReturn(new UnclassifiedAttachmentListResponse(
                        AttachmentIssue.UNCLEAR_LOCATION,
                        "장소가 명확하지 않은 첨부",
                        19L,
                        List.of(new UnclassifiedAttachmentListResponse.Item(
                                502L,
                                "https://example.test/preview",
                                AttachmentIssue.UNCLEAR_LOCATION,
                                null
                        )),
                        true,
                        "next-cursor"
                ));

        MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .build()
                .perform(get("/trips/7/unclassified-folders/UNCLEAR_LOCATION/attachments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].restoreTripPlaceId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value("next-cursor"));
    }

    @Test
    void 빈_미분류_사진_목록은_200으로_반환한다() throws Exception {
        when(unclassifiedFolderListService.findAttachments(1L, 7L, "DUPLICATED", null))
                .thenReturn(new UnclassifiedAttachmentListResponse(
                        AttachmentIssue.DUPLICATED,
                        "비슷한 첨부",
                        0L,
                        List.of(),
                        false,
                        null
                ));

        MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .build()
                .perform(get("/trips/7/unclassified-folders/DUPLICATED/attachments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("UNCLASSIFIED_ATTACHMENT_DETAIL_FOUND"))
                .andExpect(jsonPath("$.data.attachmentCount").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(Matchers.nullValue()));
    }

    @ParameterizedTest
    @CsvSource({
            "INVALID_ATTACHMENT_ISSUE, 400",
            "INVALID_CURSOR, 400",
            "TRIP_NOT_FOUND, 404",
            "INTERNAL_SERVER_ERROR, 500"
    })
    void 미분류_사진_조회_오류는_고정_메시지와_null_data를_반환한다(
            String message, int statusCode
    ) throws Exception {
        RuntimeException exception = switch (message) {
            case "INVALID_ATTACHMENT_ISSUE" -> new InvalidAttachmentIssueException();
            case "INVALID_CURSOR" -> new InvalidCursorException();
            case "TRIP_NOT_FOUND" -> new TripNotFoundException();
            default -> new IllegalStateException("preview URL creation failed");
        };
        when(unclassifiedFolderListService.findAttachments(1L, 7L, "BLURRY", null))
                .thenThrow(exception);

        MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .build()
                .perform(get("/trips/7/unclassified-folders/BLURRY/attachments"))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.data").value(Matchers.nullValue()));
    }

    @Test
    void 장소_폴더의_첨부_목록을_조회한다() throws Exception {
        when(listService.findByPlaceFolder(1L, 7L, 3L, "cursor"))
                .thenReturn(new TripAttachmentListResponse(
                        List.of(new TripAttachmentListResponse.Item(11L, "https://example.com/preview")),
                        true,
                        "next-cursor"
                ));

        MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .build()
                .perform(get("/api/trips/7/place-folders/3/attachments")
                        .contextPath("/api")
                        .servletPath("/trips/7/place-folders/3/attachments")
                        .param("cursor", "cursor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("ATTACHMENT_LIST_FOUND"))
                .andExpect(jsonPath("$.data.items[0].tripAttachmentId").value(11))
                .andExpect(jsonPath("$.data.items[0].thumbnailUrl").value("https://example.com/preview"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value("next-cursor"));

        verify(listService).findByPlaceFolder(1L, 7L, 3L, "cursor");
    }

    @Test
    void 첨부_원본_조회_결과를_반환한다() throws Exception {
        when(detailService.findDetail(1L, 11L))
                .thenReturn(new TripAttachmentDetailResponse(
                        11L,
                        "https://example.com/original"
                ));

        MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .build()
                .perform(get("/api/attachments/11")
                        .contextPath("/api")
                        .servletPath("/attachments/11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("ATTACHMENT_FOUND"))
                .andExpect(jsonPath("$.data.tripAttachmentId").value(11))
                .andExpect(jsonPath("$.data.originalUrl")
                        .value("https://example.com/original"));

        verify(detailService).findDetail(1L, 11L);
    }

    @Test
    void 첨부_다운로드_URL을_반환한다() throws Exception {
        when(downloadService.issueDownloadUrl(1L, 11L))
                .thenReturn(new TripAttachmentDownloadResponse(
                        11L,
                        "https://example.com/download"
                ));

        MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .build()
                .perform(get("/api/attachments/11/download")
                        .contextPath("/api")
                        .servletPath("/attachments/11/download"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("ATTACHMENT_DOWNLOAD_URL_ISSUED"))
                .andExpect(jsonPath("$.data.tripAttachmentId").value(11))
                .andExpect(jsonPath("$.data.downloadUrl")
                        .value("https://example.com/download"));

        verify(downloadService).issueDownloadUrl(1L, 11L);
    }

    @Test
    void API_접두사가_있는_JSON_완료_요청에_uploadId가_없으면_400을_반환한다() throws Exception {
        MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build()
                .perform(post("/api/trips/7/initial-attachments")
                        .contextPath("/api")
                        .servletPath("/trips/7/initial-attachments")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_ATTACHMENT_UPLOAD"));
        verifyNoInteractions(uploadCompletion);
    }

    @Test
    void 첨부_한_개_삭제를_서비스에_전달한다() throws Exception {
        MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .build()
                .perform(delete("/api/attachments/11")
                        .contextPath("/api")
                        .servletPath("/attachments/11"))
                .andExpect(status().isNoContent());

        verify(deletionService).deleteOne(1L, 11L);
    }

    @Test
    void 첨부_여러_개_삭제를_서비스에_전달한다() throws Exception {
        MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .build()
                .perform(post("/api/attachments/bulk-delete")
                        .contextPath("/api")
                        .servletPath("/attachments/bulk-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripAttachmentIds\":[11,12]}"))
                .andExpect(status().isNoContent());

        verify(deletionService).deleteBulk(1L, List.of(11L, 12L));
    }

    @Test
    void 첨부_여러_개의_ZIP_다운로드_URL을_반환한다() throws Exception {
        when(bulkDownloadService.issueDownloadUrl(1L, List.of(11L, 12L)))
                .thenReturn(new BulkAttachmentDownloadResponse(
                        "yeodam-attachments.zip",
                        "https://example.com/archive"
                ));

        MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .build()
                .perform(post("/api/attachments/bulk-download")
                        .contextPath("/api")
                        .servletPath("/attachments/bulk-download")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripAttachmentIds\":[11,12]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("BULK_ATTACHMENT_DOWNLOAD_URL_ISSUED"))
                .andExpect(jsonPath("$.data.fileName").value("yeodam-attachments.zip"))
                .andExpect(jsonPath("$.data.downloadUrl")
                        .value("https://example.com/archive"));

        verify(bulkDownloadService).issueDownloadUrl(1L, List.of(11L, 12L));
    }

    @Test
    void 일괄_삭제_201개_요청은_조회_없이_기존_400_응답을_반환한다() throws Exception {
        TripAttachmentRepository repository = mock(TripAttachmentRepository.class);
        UserStatsService stats = mock(UserStatsService.class);
        TripAttachmentDeletionService realDeletionService =
                new TripAttachmentDeletionService(mock(com.yeodam.yeodambe.trip.service.AdditionalAttachmentModificationGuard.class), mock(jakarta.persistence.EntityManager.class), repository, stats);
        TripAttachmentController realController = new TripAttachmentController(
                    listService,
                detailService,
                realDeletionService,
                downloadService,
                bulkDownloadService,
                uploadUrlService,
                uploadCompletion,
                additionalUploadUrlService,
            mock(com.yeodam.yeodambe.trip.service.AdditionalAttachmentUploadCompletionService.class),
                mock(UnclassifiedFolderListService.class),
                restoreService
        );
        String ids = LongStream.rangeClosed(1, 201)
                .mapToObj(Long::toString).collect(Collectors.joining(","));

        MockMvcBuilders.standaloneSetup(realController)
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build()
                .perform(post("/api/attachments/bulk-delete")
                        .contextPath("/api")
                        .servletPath("/attachments/bulk-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripAttachmentIds\":[" + ids + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_ATTACHMENT_IDS"))
                .andExpect(jsonPath("$.data").isEmpty());

        verifyNoInteractions(repository, stats);
    }

    @Test
    void 복구_입력_오류를_구분한다() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        for (String body : List.of("{}", "{\"tripPlaceId\":null}")) {
            mvc.perform(post("/attachments/11/restore")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("RESTORE_PLACE_FOLDER_REQUIRED"));
        }
        for (String body : List.of("", "{", "{\"tripPlaceId\":0}", "{\"tripPlaceId\":-1}")) {
            mvc.perform(post("/attachments/11/restore")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("INVALID_REQUEST"));
        }
        mvc.perform(post("/attachments/0/restore")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("RESTORE_PLACE_FOLDER_REQUIRED"));
        mvc.perform(post("/attachments/-1/restore")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tripPlaceId\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_REQUEST"));
        mvc.perform(post("/attachments/0/restore")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tripPlaceId\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_REQUEST"));
    }

    @Test
    void 일괄_복구_입력과_목적지_필수_우선순위를_검증한다() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        for (String body : List.of("", "{", "{}", "{\"items\":[]}", "{\"items\":[null]}",
                "{\"items\":[{\"tripAttachmentId\":null,\"tripPlaceId\":3}]}",
                "{\"items\":[{\"tripAttachmentId\":0,\"tripPlaceId\":3}]}")) {
            mvc.perform(post("/attachments/bulk-restore")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("INVALID_REQUEST"));
        }
        mvc.perform(post("/attachments/bulk-restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"tripAttachmentId\":0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("RESTORE_PLACE_FOLDER_REQUIRED"));
    }

    @Test
    void 복구_성공_응답과_일괄_순서를_유지한다() throws Exception {
        when(restoreService.restoreOne(eq(1L), eq(11L), any()))
                .thenReturn(new AttachmentRestoreResponse(
                        11L, 3L, "CLASSIFIED"));
        when(restoreService.restoreBulk(eq(1L), any()))
                .thenReturn(new BulkAttachmentRestoreResponse(
                        List.of(12L, 11L), List.of(3L, 3L)));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        mvc.perform(post("/attachments/11/restore")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tripPlaceId\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("ATTACHMENT_RESTORE_SUCCESS"))
                .andExpect(jsonPath("$.data.tripAttachmentId").value(11))
                .andExpect(jsonPath("$.data.tripPlaceId").value(3))
                .andExpect(jsonPath("$.data.classificationType").value("CLASSIFIED"));
        mvc.perform(post("/attachments/bulk-restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"tripAttachmentId\":12,\"tripPlaceId\":3},{\"tripAttachmentId\":11,\"tripPlaceId\":3}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("BULK_ATTACHMENT_RESTORE_SUCCESS"))
                .andExpect(jsonPath("$.data.restoredTripAttachmentIds[0]").value(12))
                .andExpect(jsonPath("$.data.restoredTripAttachmentIds[1]").value(11))
                .andExpect(jsonPath("$.data.tripPlaceIds[0]").value(3))
                .andExpect(jsonPath("$.data.tripPlaceIds[1]").value(3));
        verify(restoreService).restoreOne(1L, 11L,
                new AttachmentRestoreRequest(3L));
        verify(restoreService).restoreBulk(1L,
                new BulkAttachmentRestoreRequest(List.of(
                        new BulkAttachmentRestoreRequest.Item(12L, 3L),
                        new BulkAttachmentRestoreRequest.Item(11L, 3L))));
    }

    @Test
    void 일괄_복구_201개와_중복을_거부한다() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(authenticationPrincipalResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        String items = LongStream.rangeClosed(1, 201)
                .mapToObj(id -> "{\"tripAttachmentId\":" + id + ",\"tripPlaceId\":3}")
                .collect(Collectors.joining(","));
        mvc.perform(post("/attachments/bulk-restore")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[" + items + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_REQUEST"));
        verifyNoInteractions(restoreService);
        when(restoreService.restoreBulk(eq(1L), any()))
                .thenThrow(new InvalidRestoreRequestException());
        mvc.perform(post("/attachments/bulk-restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"tripAttachmentId\":11,\"tripPlaceId\":3},{\"tripAttachmentId\":11,\"tripPlaceId\":3}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    private HandlerMethodArgumentResolver authenticationPrincipalResolver() {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                          NativeWebRequest request, WebDataBinderFactory binderFactory) {
                return jwt();
            }
        };
    }

    private Jwt jwt() {
        return Jwt.withTokenValue("token").header("alg", "HS256").subject("1").build();
    }

}
