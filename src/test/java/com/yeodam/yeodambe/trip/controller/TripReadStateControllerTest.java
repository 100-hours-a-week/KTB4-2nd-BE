package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.common.exception.GlobalExceptionHandler;
import com.yeodam.yeodambe.story.controller.StorySourceFolderController;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.service.StorySourceFolderListService;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.AttachmentIssue;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TripReadStateControllerTest {
    private final TripRepository trips = mock(TripRepository.class);
    private final TripDetailPlaceRepository places = mock(TripDetailPlaceRepository.class);
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final TripRegionRepository regions = mock(TripRegionRepository.class);
    private final AdditionalAttachmentExecutionRepository executions =
            mock(AdditionalAttachmentExecutionRepository.class);
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final Trip trip = mock(Trip.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        TripAccessService access = new TripAccessService(trips);
        TripPlaceFolderListService folders = new TripPlaceFolderListService(
                access, places, attachments, storage, mapper
        );
        TripService service = new TripService(
                mock(AdditionalAttachmentModificationGuard.class), trips, regions,
                executions, mock(RegionCatalog.class), attachments, storage, access,
                mapper, mock(StoryRepository.class), mock(TripMapCacheStore.class), Clock.systemUTC(),
                places
        );
        TripController controller = new TripController(
                service, mock(TripProcessingStatusService.class),
                mock(TripProcessingCancellationService.class), folders,
                mock(TripDeletionService.class), mapper
        );
        TripAttachmentController attachmentController = new TripAttachmentController(
                new TripAttachmentListService(trips, places, attachments, storage, mapper, access),
                mock(TripAttachmentDetailService.class), mock(TripAttachmentDeletionService.class),
                mock(TripAttachmentDownloadService.class), mock(BulkAttachmentDownloadService.class),
                mock(InitialAttachmentUploadUrlService.class),
                mock(InitialAttachmentUploadCompletionService.class),
                mock(AdditionalAttachmentUploadUrlService.class),
                mock(AdditionalAttachmentUploadCompletionService.class),
                new UnclassifiedFolderListService(access, attachments, storage, mapper),
                mock(TripAttachmentRestoreService.class)
        );
        StorySourceFolderController storyController = new StorySourceFolderController(
                new StorySourceFolderListService(folders, access, executions)
        );
        mvc = MockMvcBuilders.standaloneSetup(controller, attachmentController, storyController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none").subject("1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L)).thenReturn(Optional.of(trip));
        when(trips.existsByIdAndUserIdAndDeletedAtIsNull(7L, 1L)).thenReturn(true);
        when(places.existsByIdAndTripIdAndDeletedAtIsNull(3L, 7L)).thenReturn(true);
        when(trip.getProcessingStatus()).thenReturn(ProcessingStatus.COMPLETED);
        when(trip.getId()).thenReturn(7L);
        when(trip.getStartDate()).thenReturn(LocalDate.of(2026, 10, 1));
        when(trip.getEndDate()).thenReturn(LocalDate.of(2026, 10, 2));
    }

    @Test
    void 지도는_완료된_여행의_빈_마커를_반환한다() throws Exception {
        mvc.perform(get("/trips/7/map"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_DETAIL_MAP_FOUND"))
                .andExpect(jsonPath("$.data.tripId").value(7))
                .andExpect(jsonPath("$.data.markers").isEmpty());
    }

    @Test
    void 지도는_접근할_수_없는_여행을_404로_반환한다() throws Exception {
        mvc.perform(get("/trips/8/map"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("TRIP_NOT_FOUND"));
        verify(places, never()).findMapMarkers(anyLong());
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({
            "/trips/7, TRIP_DETAIL_NOT_AVAILABLE",
            "/trips/7/map, TRIP_DETAIL_NOT_AVAILABLE",
            "/trips/7/edit, TRIP_UPDATE_NOT_ALLOWED",
            "/trips/7/place-folders, TRIP_DETAIL_NOT_AVAILABLE",
            "/trips/7/story/source-folders, STORY_GENERATION_NOT_ALLOWED",
            "/trips/7/unclassified-folders, TRIP_DETAIL_NOT_AVAILABLE",
            "/trips/7/unclassified-folders/BLURRY/attachments, TRIP_DETAIL_NOT_AVAILABLE",
            "/trips/7/place-folders/3/attachments, TRIP_DETAIL_NOT_AVAILABLE"
    })
    void 초기_분석_중에는_목록과_상세를_거절한다(String path, String message) throws Exception {
        when(trip.getProcessingStatus()).thenReturn(ProcessingStatus.PROCESSING);

        mvc.perform(get(path))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.data").isEmpty());

        verifyNoInteractions(attachments, regions, storage);
    }

    @ParameterizedTest
    @CsvSource({
            "/trips/7, TRIP_DETAIL_NOT_AVAILABLE",
            "/trips/7/map, TRIP_DETAIL_NOT_AVAILABLE",
            "/trips/7/edit, TRIP_UPDATE_NOT_ALLOWED",
            "/trips/7/story/source-folders, STORY_GENERATION_NOT_ALLOWED"
    })
    void 추가_분석_중에는_상세와_수정과_스토리_대상_조회를_거절한다(
            String path, String message
    ) throws Exception {
        when(executions.existsByTripIdAndEndedAtIsNull(7L)).thenReturn(true);

        mvc.perform(get(path))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.data").isEmpty());

        verifyNoInteractions(attachments, regions, storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/trips/7", "/trips/7/map", "/trips/7/edit", "/trips/7/place-folders",
            "/trips/7/story/source-folders", "/trips/7/unclassified-folders",
            "/trips/7/unclassified-folders/BLURRY/attachments",
            "/trips/7/place-folders/3/attachments"
    })
    void 완료된_여행은_빈_목록이어도_조회할_수_있다(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/trips/7", "/trips/7/map", "/trips/7/edit", "/trips/7/story/source-folders"})
    void 추가_실행이_종료되면_다시_조회할_수_있다(String path) throws Exception {
        when(executions.existsByTripIdAndEndedAtIsNull(7L)).thenReturn(true, false);

        mvc.perform(get(path)).andExpect(status().isConflict());
        mvc.perform(get(path)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/trips/7/place-folders", "/trips/7/unclassified-folders",
            "/trips/7/unclassified-folders/BLURRY/attachments",
            "/trips/7/place-folders/3/attachments"
    })
    void 추가_분석_중_일반_하위_목록의_기존_읽기는_유지한다(String path) throws Exception {
        when(executions.existsByTripIdAndEndedAtIsNull(7L)).thenReturn(true);

        mvc.perform(get(path)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({
            "FAILED, /trips/7/place-folders, 409, TRIP_DETAIL_NOT_AVAILABLE",
            "CANCELED, /trips/7/place-folders, 409, TRIP_DETAIL_NOT_AVAILABLE",
            "CANCELED, /trips/7, 404, TRIP_NOT_FOUND",
            "CANCELED, /trips/7/map, 404, TRIP_NOT_FOUND",
            "FAILED, /trips/7/map, 409, TRIP_DETAIL_NOT_AVAILABLE",
            "CANCELED, /trips/7/edit, 409, TRIP_UPDATE_NOT_ALLOWED"
    })
    void 실패와_취소_상태의_오류_계약을_유지한다(
            ProcessingStatus state, String path, int statusCode, String message
    ) throws Exception {
        when(trip.getProcessingStatus()).thenReturn(state);

        mvc.perform(get(path))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.message").value(message));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 잘못된_장소_폴더는_분석_상태보다_404를_우선한다(boolean processing) throws Exception {
        when(places.existsByIdAndTripIdAndDeletedAtIsNull(3L, 7L)).thenReturn(false);
        when(trip.getProcessingStatus()).thenReturn(
                processing ? ProcessingStatus.PROCESSING : ProcessingStatus.COMPLETED
        );

        mvc.perform(get("/trips/7/place-folders/3/attachments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("PLACE_FOLDER_NOT_FOUND"));

        verifyNoInteractions(attachments, storage);
    }

    @ParameterizedTest
    @CsvSource({
            "/trips/7/edit, /trips/8/edit",
            "/trips/7/edit, /trips/7/place-folders/3/attachments",
            "/trips/7/edit, /trips/7/unclassified-folders/BLURRY/attachments",
            "/trips/7/place-folders/3/attachments, /trips/7/place-folders/4/attachments",
            "/trips/7/place-folders/3/attachments, /trips/8/place-folders/3/attachments",
            "/trips/7/unclassified-folders/BLURRY/attachments, /trips/7/unclassified-folders/DUPLICATED/attachments",
            "/trips/7/unclassified-folders/BLURRY/attachments, /trips/8/unclassified-folders/BLURRY/attachments"
    })
    void 다른_여행이나_폴더에_커서를_사용하면_400이다(String sourcePath, String path) throws Exception {
        var photos = LongStream.rangeClosed(1, 19).mapToObj(id -> {
            TripAttachment photo = mock(TripAttachment.class);
            when(photo.getId()).thenReturn(id);
            when(photo.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 10, 1, 12, 0));
            return photo;
        }).toList();
        when(attachments.findForEditWithCursor(eq(7L), isNull(), isNull(), any()))
                .thenReturn(photos);
        when(attachments.findByPlaceFolderWithCursor(
                eq(7L), eq(3L), eq(ClassificationStatus.ACTIVE), isNull(), isNull(), any()
        )).thenReturn(photos);
        when(attachments.findUnclassifiedByIssueWithCursor(
                eq(7L), eq(AttachmentIssue.BLURRY), isNull(), isNull(), any()
        )).thenReturn(photos);
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(8L, 1L)).thenReturn(Optional.of(trip));
        when(trips.existsByIdAndUserIdAndDeletedAtIsNull(8L, 1L)).thenReturn(true);
        when(places.existsByIdAndTripIdAndDeletedAtIsNull(3L, 8L)).thenReturn(true);
        when(places.existsByIdAndTripIdAndDeletedAtIsNull(4L, 7L)).thenReturn(true);
        String body = mvc.perform(get(sourcePath))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var data = new ObjectMapper().readTree(body).path("data");
        var page = sourcePath.endsWith("/edit") ? data.path("attachments") : data;
        String cursor = page.path("nextCursor").asString();

        clearInvocations(attachments);
        mvc.perform(get(path).param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_CURSOR"))
                .andExpect(jsonPath("$.data").isEmpty());

        verifyNoInteractions(attachments);
    }

    @ParameterizedTest
    @CsvSource({
            "/trips/7, TRIP_NOT_FOUND",
            "/trips/7/edit, TRIP_NOT_FOUND",
            "/trips/7/place-folders, TRIP_NOT_FOUND",
            "/trips/7/story/source-folders, TRIP_NOT_FOUND",
            "/trips/7/unclassified-folders, TRIP_NOT_FOUND",
            "/trips/7/unclassified-folders/BLURRY/attachments, TRIP_NOT_FOUND",
            "/trips/7/place-folders/3/attachments, PLACE_FOLDER_NOT_FOUND"
    })
    void 접근할_수_없으면_상태보다_404를_우선한다(String path, String message) throws Exception {
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L)).thenReturn(Optional.empty());
        when(trips.existsByIdAndUserIdAndDeletedAtIsNull(7L, 1L)).thenReturn(false);
        when(trip.getProcessingStatus()).thenReturn(ProcessingStatus.PROCESSING);

        mvc.perform(get(path))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.data").isEmpty());

        verifyNoInteractions(attachments, regions, executions, storage);
    }
}
