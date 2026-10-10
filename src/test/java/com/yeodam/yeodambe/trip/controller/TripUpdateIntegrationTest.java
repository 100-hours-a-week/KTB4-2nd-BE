package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.trip.service.request.TripAttachmentMetadataRequest;
import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.session.LoginSessionIssuer;
import com.yeodam.yeodambe.user.service.UserRegistrationService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripUpdateIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TripService service;
    @Autowired private UserRegistrationService registration;
    @Autowired private LoginSessionIssuer sessions;
    @Autowired private AccessTokenIssuer tokens;
    @Autowired private CsrfTokenStore csrf;
    @MockitoSpyBean private TripRegionRepository regions;
    private Long userId;
    private Long tripId;
    private String accessToken;
    private String browser;

    @BeforeEach
    void prepareCompletedTripAndSession() {
        String unique = UUID.randomUUID().toString();
        userId = registration.register("edit-" + unique + "@yeodam.test", "수정회원",
                OAuthProvider.KAKAO, "edit-" + unique).getUserId();
        accessToken = tokens.issue(userId, sessions.issue(userId).sid());
        browser = "edit-" + unique;
        csrf.save(browser, "csrf-token");
        tripId = create("원래 여행", List.of("11000", "26000"));
        jdbc.update("UPDATE trips SET processing_status = 'COMPLETED', is_favorite = true, thumbnail_key = 'original-thumb' WHERE trip_id = ?", tripId);
    }

    @Test
    void 이름만_수정하면_같은_ID로_저장하고_기존_값을_보존한다() throws Exception {
        mvc.perform(request("{\"tripName\":\"변경 여행\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_UPDATE_SUCCESS"))
                .andExpect(jsonPath("$.data.tripId").value(tripId))
                .andExpect(jsonPath("$.data.startDate").value("2026-09-01"))
                .andExpect(jsonPath("$.data.regions.length()").value(2));
        assertThat(name()).isEqualTo("변경 여행");
        assertThat(jdbc.queryForObject("SELECT is_favorite FROM trips WHERE trip_id = ?", Boolean.class, tripId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT thumbnail_key FROM trips WHERE trip_id = ?", String.class, tripId)).isEqualTo("original-thumb");
    }

    @Test
    void 같은_이름을_유지해도_자신은_중복으로_판단하지_않는다() throws Exception {
        mvc.perform(request("{\"tripName\":\"원래 여행\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 기존_지역_ID를_보존하고_제거_지역을_soft_delete하며_추가_지역을_반환한다() throws Exception {
        Long seoulId = jdbc.queryForObject("SELECT region_id FROM trip_regions WHERE trip_id = ? AND region_code = '11000'", Long.class, tripId);
        mvc.perform(request("{\"regionCodes\":[\"11000\",\"50110\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.regions.length()").value(2))
                .andExpect(jsonPath("$.data.regions[0].regionId").value(seoulId))
                .andExpect(jsonPath("$.data.regions[1].regionCode").value("50110"));
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM trip_regions WHERE trip_id = ? AND region_code = '26000'", Boolean.class, tripId)).isTrue();
        assertThat(jdbc.queryForList("SELECT region_code FROM trip_regions WHERE trip_id = ? AND deleted_at IS NULL ORDER BY region_id", String.class, tripId)).containsExactly("11000", "50110");
    }

    @Test
    void 지역_저장에_실패하면_이름과_지역_삭제를_함께_롤백한다() throws Exception {
        doThrow(new IllegalStateException("forced region persistence failure"))
                .when(regions).saveAll(any());
        mvc.perform(request("{\"tripName\":\"변경 여행\",\"regionCodes\":[\"50110\"]}"))
                .andExpect(status().isInternalServerError());
        assertThat(name()).isEqualTo("원래 여행");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_regions WHERE trip_id = ? AND deleted_at IS NULL", Integer.class, tripId)).isEqualTo(2);
    }

    @Test
    void 다른_여행의_이름과_중복되면_409를_반환한다() throws Exception {
        create("다른 여행", List.of("11000"));
        mvc.perform(request("{\"tripName\":\"다른 여행\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("TRIP_NAME_DUPLICATED"));
        assertThat(name()).isEqualTo("원래 여행");
    }

    @Test
    void 처리중이면_409를_반환한다() throws Exception {
        jdbc.update("UPDATE trips SET processing_status = 'PROCESSING' WHERE trip_id = ?", tripId);
        mvc.perform(request("{\"tripName\":\"변경 여행\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("TRIP_UPDATE_NOT_ALLOWED"));
    }

    @Test
    void 타인_여행이나_삭제된_여행이면_404를_반환한다() throws Exception {
        Long other = registration.register("other-" + UUID.randomUUID() + "@yeodam.test", "다른회원",
                OAuthProvider.KAKAO, UUID.randomUUID().toString()).getUserId();
        Long otherTrip = service.createTrip(other, new TripCreateRequest("타인 여행", LocalDate.of(2026,9,1), LocalDate.of(2026,9,3), List.of("11000"),
                List.of(new TripAttachmentMetadataRequest(
                        OffsetDateTime.parse("2026-10-11T10:30:00+09:00"), null, null)))).tripId();
        mvc.perform(request("{\"tripName\":\"변경 여행\"}").with(r -> { r.setRequestURI("/api/trips/" + otherTrip); return r; }))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE trips SET deleted_at = CURRENT_TIMESTAMP WHERE trip_id = ?", tripId);
        mvc.perform(request("{\"tripName\":\"변경 여행\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 빈_수정과_잘못된_기간과_없는_지역은_400으로_거부한다() throws Exception {
        for (String body : List.of("{}", "{\"startDate\":\"2026-09-04\"}", "{\"regionCodes\":[\"99999\"]}")) {
            mvc.perform(request(body)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("INVALID_TRIP_REQUEST"));
        }
        assertThat(name()).isEqualTo("원래 여행");
    }

    @Test
    void JWT가_없으면_401이고_CSRF가_틀리면_403이다() throws Exception {
        mvc.perform(patch("/api/trips/" + tripId).contextPath("/api")
                        .cookie(new Cookie("CSRF_CONTEXT", browser)).header("X-CSRF-TOKEN", "csrf-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tripName\":\"변경\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(request("{\"tripName\":\"변경\"}", "wrong-token"))
                .andExpect(status().isForbidden());
        assertThat(name()).isEqualTo("원래 여행");
    }

    @Test
    void 깨진_JSON도_명세의_수정_입력_오류로_반환한다() throws Exception {
        mvc.perform(request("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_TRIP_REQUEST"));
    }

    private Long create(String name, List<String> codes) {
        return service.createTrip(userId, new TripCreateRequest(name, LocalDate.of(2026,9,1), LocalDate.of(2026,9,3), codes,
                List.of(new TripAttachmentMetadataRequest(
                        OffsetDateTime.parse("2026-10-11T10:30:00+09:00"), null, null)))).tripId();
    }

    private String name() {
        return jdbc.queryForObject("SELECT trip_name FROM trips WHERE trip_id = ?", String.class, tripId);
    }

    private MockHttpServletRequestBuilder request(String body) {
        return request(body, "csrf-token");
    }

    private MockHttpServletRequestBuilder request(String body, String csrfToken) {
        return patch("/api/trips/" + tripId).contextPath("/api")
                .cookie(new Cookie("accessToken", accessToken), new Cookie("CSRF_CONTEXT", browser))
                .header("X-CSRF-TOKEN", csrfToken)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
