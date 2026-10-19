package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.response.TripCreateResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

class MigrationTripApplyServiceTest {
    private final MigrationTripResultRepository results = mock(MigrationTripResultRepository.class);
    private final TripService trips = mock(TripService.class);
    private final MigrationTripApplyService service = new MigrationTripApplyService(results, trips);
    private final MigrationTripCommand command = new MigrationTripCommand(
            "51961261-7761-4587-ae2b-55c0452924bd", 7L, "hash", "강릉",
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), List.of("42150"));

    @Test
    void 새_요청은_여행과_결과를_함께_기록한다() {
        when(results.find(command.requestId())).thenReturn(Optional.empty());
        when(trips.createTrip(eq(7L), any()))
                .thenReturn(new TripCreateResponse(42L, ProcessingStatus.PROCESSING));

        service.apply(command);

        var ordered = inOrder(results, trips);
        ordered.verify(results).insertApplying(command);
        ordered.verify(trips).createTrip(7L, command.tripRequest());
        ordered.verify(results).complete(command.requestId(), 42L);
    }

    @Test
    void 같은_요청의_재소비는_여행을_다시_만들지_않는다() {
        when(results.find(command.requestId())).thenReturn(Optional.of(
                new MigrationTripResultRepository.Entry(
                        command.requestId(), 7L, "hash", "COMPLETED", 42L, null)));

        service.apply(command);

        verifyNoInteractions(trips);
        verify(results, never()).insertApplying(any());
    }
}
