package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.trip.service.RegionCatalog;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MigrationTripAdmissionServiceTest {
    private static final String ID = "51961261-7761-4587-ae2b-55c0452924bd";
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final ObjectMapper mapper = mock(ObjectMapper.class);
    private final TripService trips = mock(TripService.class);
    private final RegionCatalog regions = mock(RegionCatalog.class);
    private final MigrationTripAdmissionService service = new MigrationTripAdmissionService(
            kafka, mapper, trips, regions, "migration-requests", Duration.ofMillis(100));
    private final TripCreateRequest request = new TripCreateRequest(
            "강릉", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), List.of("42150"));

    @Test
    void broker_ack_확인_뒤에만_수락한다() {
        when(mapper.writeValueAsBytes(any())).thenReturn("canonical".getBytes());
        when(mapper.writeValueAsString(any())).thenReturn("command-json");
        when(kafka.send("migration-requests", "7", "command-json"))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThat(service.accept(7L, request, ID).status()).isEqualTo("ACCEPTED");
        verify(trips).validateTrip(request);
        verify(trips, never()).createTrip(any(), any());
    }

    @Test
    void ack_실패는_수락으로_답하지_않는다() {
        when(mapper.writeValueAsBytes(any())).thenReturn("canonical".getBytes());
        when(mapper.writeValueAsString(any())).thenReturn("command-json");
        var failed = new CompletableFuture<org.springframework.kafka.support.SendResult<String, String>>();
        failed.completeExceptionally(new IllegalStateException("broker down"));
        when(kafka.send("migration-requests", "7", "command-json")).thenReturn(failed);

        assertThatThrownBy(() -> service.accept(7L, request, ID))
                .isInstanceOf(MigrationTripAdmissionException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void queue_모드에서_클라이언트_요청_id를_반드시_요구한다() {
        assertThatThrownBy(() -> service.accept(7L, request, null))
                .isInstanceOf(MigrationTripAdmissionException.class);
        verifyNoInteractions(kafka);
    }
}
