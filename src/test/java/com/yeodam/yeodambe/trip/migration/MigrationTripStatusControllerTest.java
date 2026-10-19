package com.yeodam.yeodambe.trip.migration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MigrationTripStatusControllerTest {
    private static final String ID = "51961261-7761-4587-ae2b-55c0452924bd";
    private final MigrationTripResultRepository results = mock(MigrationTripResultRepository.class);
    private final Jwt user = Jwt.withTokenValue("token")
            .header("alg", "HS256").subject("7").build();

    @Test
    void source에서는_결과_테이블을_조회하지_않는다() {
        var controller = new MigrationTripStatusController(results, false);

        assertThat(controller.status(ID, user).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(results);
    }

    @Test
    void target에서_미적용_요청은_pending이고_완료_요청은_trip_id를_보여준다() {
        var controller = new MigrationTripStatusController(results, true);
        when(results.find(ID)).thenReturn(Optional.empty(), Optional.of(
                new MigrationTripResultRepository.Entry(ID, 7L, "hash", "COMPLETED", 42L, null)));

        assertThat(controller.status(ID, user).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var completed = controller.status(ID, user);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completed.getBody().data().tripId()).isEqualTo(42L);
    }

    @Test
    void 다른_사용자의_요청_상태는_노출하지_않는다() {
        var controller = new MigrationTripStatusController(results, true);
        when(results.find(ID)).thenReturn(Optional.of(
                new MigrationTripResultRepository.Entry(ID, 9L, "hash", "COMPLETED", 42L, null)));

        assertThat(controller.status(ID, user).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
