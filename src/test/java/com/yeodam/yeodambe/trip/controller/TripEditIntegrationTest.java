package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.trip.service.request.TripAttachmentMetadataRequest;
import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripEditIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TripService service;
    @Autowired private UserRegistrationService registration;
    @Autowired private LoginSessionIssuer sessions;
    @Autowired private AccessTokenIssuer tokens;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private StoredFileRepository files;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private TripAttachmentStorageClient storage;
    private Long userId;
    private Long tripId;
    private String accessToken;
    private int createdTripCount;

    @BeforeEach
    void prepare() {
        createdTripCount = 0;
        userId = register();
        accessToken = tokens.issue(userId, sessions.issue(userId).sid());
        tripId = create(userId);
        when(storage.createReadUrl(anyString())).thenAnswer(i -> "https://test.example/" + i.getArgument(0));
    }

    @Test
    void 사진이_없는_수정_화면은_기본정보와_빈_목록을_반환한다() throws Exception {
        mvc.perform(request(tripId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_EDIT_FOUND"))
                .andExpect(jsonPath("$.data.tripId").value(tripId))
                .andExpect(jsonPath("$.data.tripName").value("수정 여행"))
                .andExpect(jsonPath("$.data.startDate").value("2026-09-01"))
                .andExpect(jsonPath("$.data.endDate").value("2026-09-03"))
                .andExpect(jsonPath("$.data.regions[0].regionCode").value("11000"))
                .andExpect(jsonPath("$.data.attachmentCount").value(0))
                .andExpect(jsonPath("$.data.attachments.items").isEmpty())
                .andExpect(jsonPath("$.data.attachments.hasNext").value(false))
                .andExpect(jsonPath("$.data.attachments.nextCursor").isEmpty());
    }

    @Test
    void 같은_시각의_19장은_ID_내림차순으로_중복이나_누락없이_두_페이지로_조회한다() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 19; i++) ids.add(photo(tripId).getId());
        jdbc.update("UPDATE trip_attachments SET created_at = '2026-09-01 12:00:00' WHERE trip_id = ?", tripId);
        Collections.reverse(ids);
        String body = mvc.perform(request(tripId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachmentCount").value(19))
                .andExpect(jsonPath("$.data.attachments.items.length()").value(18))
                .andExpect(jsonPath("$.data.attachments.hasNext").value(true))
                .andReturn().getResponse().getContentAsString();
        var page = mapper.readTree(body).path("data").path("attachments");
        for (int i = 0; i < 18; i++) {
            assertThat(page.path("items").get(i).path("tripAttachmentId").asLong()).isEqualTo(ids.get(i));
        }
        mvc.perform(request(tripId).param("cursor", page.path("nextCursor").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachmentCount").value(19))
                .andExpect(jsonPath("$.data.attachments.items.length()").value(1))
                .andExpect(jsonPath("$.data.attachments.items[0].tripAttachmentId").value(ids.getLast()))
                .andExpect(jsonPath("$.data.attachments.hasNext").value(false))
                .andExpect(jsonPath("$.data.attachments.nextCursor").isEmpty());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_attachments WHERE trip_id = ?", Long.class, tripId)).isEqualTo(19);
    }

    @Test
    void 정확히_18장이면_마지막_페이지다() throws Exception {
        for (int i = 0; i < 18; i++) photo(tripId);
        mvc.perform(request(tripId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachments.items.length()").value(18))
                .andExpect(jsonPath("$.data.attachments.hasNext").value(false))
                .andExpect(jsonPath("$.data.attachments.nextCursor").isEmpty());
    }

    @Test
    void 분류와_미분류를_포함하되_삭제된_첨부와_파일과_다른_여행_사진을_제외한다() throws Exception {
        TripAttachment active = photo(tripId);
        jdbc.update("UPDATE trip_attachments SET classification_status = 'ACTIVE' WHERE trip_attachment_id = ?", active.getId());
        TripAttachment unclassified = photo(tripId);
        TripAttachment deletedAttachment = photo(tripId);
        jdbc.update("UPDATE trip_attachments SET deleted_at = CURRENT_TIMESTAMP WHERE trip_attachment_id = ?", deletedAttachment.getId());
        TripAttachment deletedFile = photo(tripId);
        jdbc.update("UPDATE files SET deleted_at = CURRENT_TIMESTAMP WHERE file_id = ?", deletedFile.getFileId());
        photo(create(userId));
        mvc.perform(request(tripId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachmentCount").value(2))
                .andExpect(jsonPath("$.data.attachments.items.length()").value(2))
                .andExpect(jsonPath("$.data.attachments.items[0].tripAttachmentId").value(unclassified.getId()))
                .andExpect(jsonPath("$.data.attachments.items[1].tripAttachmentId").value(active.getId()))
                .andExpect(jsonPath("$.data.attachments.items[0].thumbnailUrl")
                        .value("https://test.example/" + unclassified.getPreviewStorageKey()));
    }

    @Test
    void 타인과_없는_여행과_삭제된_여행은_404다() throws Exception {
        mvc.perform(request(create(register()))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("TRIP_NOT_FOUND"));
        mvc.perform(request(Long.MAX_VALUE)).andExpect(status().isNotFound());
        jdbc.update("UPDATE trips SET deleted_at = CURRENT_TIMESTAMP WHERE trip_id = ?", tripId);
        mvc.perform(request(tripId)).andExpect(status().isNotFound());
    }

    @Test
    void 완료되지_않은_여행은_409다() throws Exception {
        for (String state : List.of("PROCESSING", "FAILED", "CANCELED")) {
            jdbc.update("UPDATE trips SET processing_status = ? WHERE trip_id = ?", state, tripId);
            mvc.perform(request(tripId)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("TRIP_UPDATE_NOT_ALLOWED"));
        }
    }

    @Test
    void 잘못된_커서는_400이고_인증이_없으면_401이다() throws Exception {
        for (String cursor : List.of("", "invalid-cursor")) {
            mvc.perform(request(tripId).param("cursor", cursor))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("INVALID_CURSOR"));
        }
        mvc.perform(get("/api/trips/" + tripId + "/edit").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    private Long register() {
        String unique = UUID.randomUUID().toString();
        return registration.register("view-" + unique + "@yeodam.test", "조회회원",
                OAuthProvider.KAKAO, unique).getUserId();
    }

    private Long create(Long owner) {
        String name = createdTripCount++ == 0 ? "수정 여행" : "수정 여행" + createdTripCount;
        Long id = service.createTrip(owner, new TripCreateRequest(name,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3), List.of("11000"),
                List.of(new TripAttachmentMetadataRequest(
                        OffsetDateTime.parse("2026-10-11T10:30:00+09:00"), null, null)))).tripId();
        jdbc.update("UPDATE trips SET processing_status = 'COMPLETED' WHERE trip_id = ?", id);
        return id;
    }

    private TripAttachment photo(Long targetTripId) {
        String key = UUID.randomUUID().toString();
        StoredFile file = StoredFile.uploaded(userId, key + ".jpg", "original/" + key, "image/jpeg");
        file.storageSize(10L);
        files.saveAndFlush(file);
        TripAttachment attachment = TripAttachment.initial(targetTripId, file.getId(), "analyze/" + key, "preview/" + key);
        attachment.storageSizes(5L, 3L, null);
        return attachments.saveAndFlush(attachment);
    }

    private MockHttpServletRequestBuilder request(Long targetTripId) {
        return get("/api/trips/" + targetTripId + "/edit").contextPath("/api")
                .cookie(new Cookie("accessToken", accessToken));
    }
}
