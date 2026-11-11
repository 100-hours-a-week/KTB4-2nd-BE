package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.common.exception.InvalidTripListFilterException;
import com.yeodam.yeodambe.common.exception.InvalidTripRequestException;
import com.yeodam.yeodambe.common.exception.GlobalExceptionHandler;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.trip.controller.TripController;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.service.TripDeletionService;
import com.yeodam.yeodambe.trip.service.TripPlaceFolderListService;
import com.yeodam.yeodambe.trip.service.TripProcessingCancellationService;
import com.yeodam.yeodambe.trip.service.TripProcessingStatusService;
import com.yeodam.yeodambe.trip.service.ShareTripService;
import com.yeodam.yeodambe.trip.service.InvitedTripListService;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.TripUpdateService;
import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import com.yeodam.yeodambe.trip.service.request.cursor.TripListCursor;
import com.yeodam.yeodambe.trip.service.request.TripListRequest;
import com.yeodam.yeodambe.trip.service.request.TripSort;
import com.yeodam.yeodambe.trip.service.request.TripUpdateRequest;
import com.yeodam.yeodambe.trip.service.response.TripCreateResponse;
import com.yeodam.yeodambe.trip.service.response.TripListResponse;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import com.yeodam.yeodambe.trip.service.response.TripUpdateResponse;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TripControllerTest {
    private final TripService tripService = mock(TripService.class);
    private final TripUpdateService tripUpdateService = mock(TripUpdateService.class);
    private final TripProcessingStatusService processingStatusService =
            mock(TripProcessingStatusService.class);
    private final TripProcessingCancellationService cancellationService =
            mock(TripProcessingCancellationService.class);
    private final TripPlaceFolderListService placeFolderListService =
            mock(TripPlaceFolderListService.class);
    private final TripDeletionService deletionService = mock(TripDeletionService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TripController controller = new TripController(
            tripService, tripUpdateService, processingStatusService, cancellationService, placeFolderListService,
            deletionService, objectMapper, mock(ShareTripService.class), mock(InvitedTripListService.class));
    private final TripCreateRequest request = new TripCreateRequest(
            "여행",
            LocalDate.now(),
            LocalDate.now(),
            List.of("50110")
    );

    @Test
    void 양수인_미존재_여행은_404를_유지한다() throws Exception {
        when(tripService.findTripDetail(7L, 1L)).thenThrow(new TripNotFoundException());

        mvc().perform(get("/trips/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("TRIP_NOT_FOUND"));
        verify(tripService).findTripDetail(7L, 1L);
    }

    @ParameterizedTest
    @CsvSource({
            "PATCH, /trips/{id}, {\"tripName\":\"여행\"}",
            "POST, /trips/{id}/favorite,",
            "DELETE, /trips/{id}/favorite,",
            "GET, /trips/{id}/processing-status,",
            "DELETE, /trips/{id}/processing,",
            "DELETE, /trips/{id},",
            "GET, /trips/{id},",
            "GET, /trips/{id}/edit,",
            "GET, /trips/{id}/place-folders,"
    })
    void 여행_경로의_양수가_아닌_ID는_서비스_호출_없이_400을_반환한다(
            String method,
            String path,
            String body
    ) throws Exception {
        MockMvc mockMvc = mvc();
        for (String tripId : List.of("0", "-1")) {
            mockMvc.perform(request(HttpMethod.valueOf(method), path.replace("{id}", tripId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body == null ? "" : body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(tripService, tripUpdateService, processingStatusService,
                cancellationService, placeFolderListService, deletionService);
    }

    private MockMvc mvc() {
        return standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
                    }

                    @Override
                    public Object resolveArgument(
                            MethodParameter parameter,
                            ModelAndViewContainer container,
                            NativeWebRequest request,
                            WebDataBinderFactory binderFactory
                    ) {
                        return jwt();
                    }
                })
                .build();
    }

    @Test
    void 공유_목록의_미지원_PUT은_405와_GET_POST_허용_헤더를_반환한다() throws Exception {
        standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build()
                .perform(put("/trips/7/shares"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("POST")))
                .andExpect(jsonPath("$.message").value("METHOD_NOT_ALLOWED"));
        verifyNoInteractions(tripService, tripUpdateService);
    }

    @Test
    void 지도_응답은_지역_그룹_필드_없이_여행_마커를_포함한다() {
        var data = new com.yeodam.yeodambe.trip.service.response.TripMapResponse(List.of(
                new com.yeodam.yeodambe.trip.service.response.TripMapResponse.Marker(7L, "trip",
                        new java.math.BigDecimal("37.5"), new java.math.BigDecimal("127.2"), "https://example.test/preview", 3L)));
        when(tripService.findMap(1L)).thenReturn(data);
        var response = controller.findMap(jwt());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var root = objectMapper.valueToTree(response.getBody());
        assertThat(root.path("message").asString()).isEqualTo("TRIP_MAP_FOUND");
        var marker = root.path("data").path("markers").get(0);
        assertThat(marker.propertyNames()).containsExactlyInAnyOrder(
                "tripId", "tripName", "latitude", "longitude", "thumbnailUrl", "attachmentCount");
        assertThat(marker.path("tripId").asLong()).isEqualTo(7L);
        assertThat(marker.path("thumbnailUrl").asString()).isEqualTo("https://example.test/preview");
        verify(tripService).findMap(1L);
    }

    @Test
    void 인증된_사용자의_여행을_생성하면_처리중으로_응답한다() {
        when(tripService.createTrip(1L, request))
                .thenReturn(new TripCreateResponse(7L, ProcessingStatus.PROCESSING));

        var response = controller.createTrip(request, Jwt.withTokenValue("token")
                .header("alg", "HS256").subject("1").build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().message()).isEqualTo("TRIP_CREATED");
        assertThat(response.getBody().data().tripId()).isEqualTo(7L);
        assertThat(response.getBody().data().status()).isEqualTo(ProcessingStatus.PROCESSING);
        verify(tripService).createTrip(1L, request);
    }

    @Test
    void 소유한_여행의_처리_상태를_조회한다() {
        var status = new TripProcessingStatusResponse(
                7L, "execution-id", 1L, TripProcessingStatusResponse.Status.FINALIZING, null, null, null);
        when(processingStatusService.findStatus(7L, 1L)).thenReturn(status);

        var response = controller.getProcessingStatus(7L, jwt());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().message()).isEqualTo("TRIP_PROCESSING_STATUS_FOUND");
        assertThat(response.getBody().data()).isSameAs(status);
        assertThat(response.getBody().data().status()).isEqualTo(TripProcessingStatusResponse.Status.FINALIZING);
        assertThat(objectMapper.valueToTree(response.getBody()).path("data").has("progress")).isFalse();
        assertThat(objectMapper.valueToTree(response.getBody()).path("data").has("analysisExecutionId")).isFalse();
        assertThat(objectMapper.valueToTree(response.getBody()).path("data").has("analysisState")).isFalse();
        assertThat(objectMapper.valueToTree(response.getBody()).path("data").has("blockedReason")).isFalse();
    }

    @Test
    void 소유한_여행의_생성_처리를_취소하면_본문_없이_204를_반환한다() {
        var response = controller.cancelProcessing(7L, jwt());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        verify(cancellationService).cancel(7L, 1L);
    }

    @Test
    void 여행_목록_조회_필터와_인증_사용자를_서비스에_전달한다() {
        TripListResponse result = new TripListResponse(List.of(), false, null);
        when(tripService.findTrips(eq(1L), any(TripListRequest.class))).thenReturn(result);

        var response = controller.findTrips(null, "OLDEST", "true", jwt());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().message()).isEqualTo("TRIP_LIST_FOUND");
        assertThat(response.getBody().data()).isSameAs(result);
        verify(tripService).findTrips(eq(1L), argThat(request ->
                request.sort() == TripSort.OLDEST && request.favorite()));
    }

    @Test
    void 수정_요청은_JWT_회원_ID로_전달하고_성공_응답을_감싼다() {
        TripUpdateRequest request = new TripUpdateRequest(
                "수정 여행", null, null, null);
        TripUpdateResponse data = new TripUpdateResponse(
                7L, "수정 여행", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3), List.of());
        BindingResult binding = mock(BindingResult.class);
        when(tripUpdateService.updateTrip(7L, 1L, request)).thenReturn(data);
        ResponseEntity<ApiResponse<TripUpdateResponse>> response = controller.updateTrip(7L, request, binding, jwt());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().message()).isEqualTo("TRIP_UPDATE_SUCCESS");
        assertThat(response.getBody().data()).isSameAs(data);
        verify(tripUpdateService).updateTrip(7L, 1L, request);
    }

    @Test
    void 수정_DTO_검증에_실패하면_서비스를_호출하지_않는다() {
        BindingResult binding = mock(BindingResult.class);
        when(binding.hasErrors()).thenReturn(true);
        Assertions.assertThatThrownBy(() -> controller.updateTrip(
                7L, new TripUpdateRequest(
                        null, null, null, null), binding, jwt()))
                .isInstanceOf(InvalidTripRequestException.class);
        verifyNoInteractions(tripService, tripUpdateService);
    }

    @Test
    void JSON_커서를_해석해_조회_서비스에_전달한다() {
        TripListCursor cursor = new TripListCursor(
                TripSort.LATEST, false, true, LocalDate.of(2026, 9, 22), 17L
        );
        when(tripService.findTrips(eq(1L), any(TripListRequest.class)))
                .thenReturn(new TripListResponse(List.of(), false, null));

        controller.findTrips(cursor.encode(objectMapper), "LATEST", "false", jwt());

        verify(tripService).findTrips(eq(1L), argThat(request -> cursor.equals(request.cursor())));
    }

    @Test
    void 잘못된_커서는_조회_서비스를_호출하기_전에_거절한다() {
        assertThatThrownBy(() -> controller.findTrips("not-base64!", null, null, jwt()))
                .isInstanceOf(InvalidTripListFilterException.class);
        verifyNoInteractions(tripService);
    }

    private Jwt jwt() {
        return Jwt.withTokenValue("token").header("alg", "HS256").subject("1").build();
    }
}
