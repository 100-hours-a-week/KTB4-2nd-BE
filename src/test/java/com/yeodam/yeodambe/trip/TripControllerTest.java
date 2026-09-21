package com.yeodam.yeodambe.trip;

import com.yeodam.yeodambe.trip.controller.TripController;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import com.yeodam.yeodambe.trip.service.response.TripCreateResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TripControllerTest {
    private final TripService tripService = mock(TripService.class);
    private final TripController controller = new TripController(tripService);
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

        var response = controller.createTrip(request, 1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().message()).isEqualTo("TRIP_CREATED");
        assertThat(response.getBody().data().tripId()).isEqualTo(7L);
        assertThat(response.getBody().data().status()).isEqualTo(ProcessingStatus.PROCESSING);
    }
}
