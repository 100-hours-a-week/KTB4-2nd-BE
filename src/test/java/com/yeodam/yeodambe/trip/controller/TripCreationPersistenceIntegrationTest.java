package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.service.UserRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripCreationPersistenceIntegrationTest {

    @Autowired
    private TripService tripService;

    @Autowired
    private UserRegistrationService registration;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private TripRegionRepository regions;

    private Long userId;

    @BeforeEach
    void registerUser() {
        String unique = UUID.randomUUID().toString();
        userId = registration.register(
                "create-" + unique + "@yeodam.test",
                "생성회원",
                OAuthProvider.KAKAO,
                unique
        ).getUserId();
    }

    @Test
    void 메타데이터_없이_여행과_지역을_저장하고_업로드를_시작하지_않는다() {
        var response = tripService.createTrip(userId, request("😀".repeat(10)));

        assertThat(response.status()).isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(tripCount()).isEqualTo(1);
        assertThat(regionCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT trip_name FROM trips WHERE trip_id = ?",
                String.class,
                response.tripId()
        )).isEqualTo("😀".repeat(10));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM initial_attachment_upload_batches WHERE trip_id = ?",
                Integer.class,
                response.tripId()
        )).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM trip_attachments WHERE trip_id = ?",
                Integer.class,
                response.tripId()
        )).isZero();
    }

    @Test
    void 지역_저장_실패면_여행도_롤백한다() {
        doThrow(new IllegalStateException("forced region persistence failure"))
                .when(regions).saveAll(any());

        assertThatThrownBy(() -> tripService.createTrip(userId, request("제주 여행")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(tripCount()).isZero();
        assertThat(regionCount()).isZero();
    }

    private TripCreateRequest request(String name) {
        return new TripCreateRequest(
                name,
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 2),
                List.of("50110")
        );
    }

    private int tripCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM trips WHERE user_id = ?",
                Integer.class,
                userId
        );
    }

    private int regionCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM trip_regions r JOIN trips t ON t.trip_id = r.trip_id WHERE t.user_id = ?",
                Integer.class,
                userId
        );
    }
}
