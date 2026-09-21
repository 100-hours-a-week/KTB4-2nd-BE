package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.trip.controller.TripController;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.TripProcessingStatusService;
import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import com.yeodam.yeodambe.trip.service.response.TripCreateResponse;
import com.yeodam.yeodambe.trip.service.response.TripMapResponse;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TripControllerTest {
    private final TripService tripService = mock(TripService.class);
    private final TripProcessingStatusService processingStatusService =
            mock(TripProcessingStatusService.class);
    private final TripController controller = new TripController(tripService, processingStatusService);
    private final TripCreateRequest request = new TripCreateRequest(
            "여행", LocalDate.now(), LocalDate.now(), List.of("50110"));

    @Test
    void 사용자_아이디가_없으면_저장하지_않는다() {
        var response = controller.createTrip(request, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().message()).isEqualTo("UNAUTHORIZED");
        verifyNoInteractions(tripService);
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
    }

    @Test
    void 인증된_사용자의_메인_지도_조회_결과를_반환한다() {
        Jwt jwt = mock(Jwt.class);
        TripMapResponse data = new TripMapResponse(List.of());
        when(jwt.getSubject()).thenReturn("1");
        when(tripService.findMap(1L)).thenReturn(data);

        var response = controller.findMap(jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().message()).isEqualTo("TRIP_MAP_FOUND");
        assertThat(response.getBody().data().markers()).isEmpty();
        verify(tripService).findMap(1L);
    }

    @Test
    void 인증_정보가_없으면_처리_상태를_조회하지_않는다() {
        var response = controller.getProcessingStatus(7L, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().message()).isEqualTo("UNAUTHORIZED");
        verifyNoInteractions(processingStatusService);
    }

    @Test
    void 소유한_여행의_처리_상태를_조회한다() {
        var status = new TripProcessingStatusResponse(
                7L, ProcessingStatus.PROCESSING, null, null, null, null);
        when(processingStatusService.findStatus(7L, 1L)).thenReturn(status);

        var response = controller.getProcessingStatus(7L, jwt());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().message()).isEqualTo("TRIP_PROCESSING_STATUS_FOUND");
        assertThat(response.getBody().data()).isSameAs(status);
    }

    private Jwt jwt() {
        return Jwt.withTokenValue("token").header("alg", "HS256").subject("1").build();
    }
}
