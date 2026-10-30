package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.RegionOrigin;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripEditNotificationOutboxRepository;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import jakarta.servlet.http.Cookie;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripRegion;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "auth.websocket.internal-service-token=edit-test-token")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripEditCommandIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripRegionRepository regions;
    @Autowired private TripShareUserRepository shares;
    @Autowired private AccessTokenIssuer tokens;
    @Autowired private LoginSessionStore sessions;
    @Autowired private CsrfTokenStore csrfTokens;
    @Autowired private StoredFileRepository files;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private UserStatsRepository stats;
    @MockitoSpyBean private TripEditNotificationOutboxRepository notifications;
    private Long ownerId;
    private Long readerId;
    private Trip trip;

    @BeforeEach
    void fixture() {
        ownerId = users.save(new User(UUID.randomUUID() + "@edit.test", "소유자")).getUserId();
        readerId = users.save(new User(UUID.randomUUID() + "@edit.test", "공유자")).getUserId();
        trip = new Trip(ownerId, "원래여행", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3));
        ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
        trip = trips.saveAndFlush(trip);
        regions.saveAndFlush(new TripRegion(trip, "11000", "서울", new BigDecimal("37.5"), new BigDecimal("127.0")));
    }

    @Test
    void 같은_자동저장_요청은_결과를_재사용하고_변경_번호를_다시_증가시키지_않는다() throws Exception {
        String request = "{\"requestId\":\"save\",\"changes\":{\"tripName\":\"바뀐여행\"}}";
        update(ownerId, request).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_UPDATE_SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.sequence").value(1));
        update(ownerId, request).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.sequence").value(1));
        assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_edit_command_records WHERE user_id = ?",
                Integer.class, ownerId)).isEqualTo(1);
    }

    @Test
    void READ의_수정은_거절_결과로_기록하고_여행은_보존한다() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        shares.saveAndFlush(new TripShareUser(trip.getId(), ownerId, readerId,
                TripSharePermission.READ, now.plusHours(1), now));
        update(readerId, "{\"requestId\":\"read\",\"changes\":{\"tripName\":\"금지변경\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("WRITE_PERMISSION_REQUIRED"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
        assertThat(trips.findById(trip.getId()).orElseThrow().getTripName()).isEqualTo("원래여행");
    }

    @Test
    void 서비스_토큰_없이_내부_명령을_호출할_수_없다() throws Exception {
        mvc.perform(post(path()).with(jwt().jwt(token -> token.subject(ownerId.toString()))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"blocked\",\"changes\":{\"tripName\":\"변경\"}}"))
                .andExpect(status().isForbidden());
        assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isZero();
    }

    @Test
    void WRITE는_수정하고_재시도는_개인결과만_추가한다() throws Exception {
        share(TripSharePermission.WRITE);
        String body = "{\"requestId\":\"write\",\"changes\":{\"tripName\":\"공유수정\"}}";
        update(readerId, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        update(readerId, body).andExpect(status().isOk());
        assertThat(notificationCount("TRIP_EVENT")).isEqualTo(1);
        assertThat(notificationCount("COMMAND_RESULT")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT event_id) FROM trip_edit_notification_outbox WHERE target_type = 'USER' AND target_id = ?",
                Integer.class, readerId)).isEqualTo(1);
    }

    @Test
    void 거절후_권한이_상승해도_같은_요청은_다시_실행하지_않는다() throws Exception {
        share(TripSharePermission.READ);
        String body = "{\"requestId\":\"denied\",\"changes\":{\"tripName\":\"공유수정\"}}";
        update(readerId, body).andExpect(jsonPath("$.data.status").value("REJECTED"));
        jdbc.update("UPDATE trip_share_users SET permission = 'WRITE' WHERE trip_id = ?", trip.getId());
        update(readerId, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("WRITE_PERMISSION_REQUIRED"));
        assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isZero();
    }

    @Test
    void 잘못된_변경도_결과를_기록하고_본문이_달라지면_충돌이다() throws Exception {
        update(ownerId, "{\"requestId\":\"invalid\",\"changes\":{\"startDate\":\"2026-10-01\"}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("INVALID_TRIP_REQUEST"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
        update(ownerId, "{\"requestId\":\"invalid\",\"changes\":{\"tripName\":\"새이름\"}}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("TRIP_EDIT_COMMAND_MISMATCH"));
        assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isZero();
    }

    @Test
    void 객체_필드_순서만_달라진_요청은_같은_명령이다() throws Exception {
        update(ownerId, "{\"requestId\":\"order\",\"changes\":{\"tripName\":\"순서검증\",\"startDate\":\"2026-10-01\",\"endDate\":\"2026-10-03\"}}")
                .andExpect(status().isOk());
        update(ownerId, "{\"requestId\":\"order\",\"changes\":{\"endDate\":\"2026-10-03\",\"startDate\":\"2026-10-01\",\"tripName\":\"순서검증\"}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sequence").value(1));
        assertThat(notificationCount("TRIP_EVENT")).isEqualTo(1);
    }

    @Test
    void 만료된_요청은_다시_실행하지_않는다() throws Exception {
        String body = "{\"requestId\":\"expired\",\"changes\":{\"tripName\":\"만료검증\"}}";
        update(ownerId, body).andExpect(status().isOk());
        jdbc.update("UPDATE trip_edit_command_records SET result_expires_at = ? WHERE user_id = ?",
                LocalDateTime.now().minusMinutes(1), ownerId);
        update(ownerId, body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("TRIP_EDIT_COMMAND_RESULT_EXPIRED"));
        assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isEqualTo(1);
    }

    @Test
    void 변경이_없으면_공유_이벤트와_변경번호를_늘리지_않는다() throws Exception {
        update(ownerId, "{\"requestId\":\"noop\",\"changes\":{\"tripName\":\"원래여행\"}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sequence").value(0));
        assertThat(notificationCount("TRIP_EVENT")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_edit_notification_outbox WHERE event_type = 'COMMAND_RESULT' AND target_id = ?",
                Integer.class, ownerId)).isEqualTo(1);
    }

    @Test
    void Outbox_저장_실패는_여행과_결과_기록을_함께_롤백한다() throws Exception {
        doThrow(new DataAccessResourceFailureException("test outbox unavailable"))
                .when(notifications).saveAndFlush(any());
        update(ownerId, "{\"requestId\":\"rollback\",\"changes\":{\"tripName\":\"실패변경\"}}")
                .andExpect(status().isServiceUnavailable());
        Trip unchanged = trips.findById(trip.getId()).orElseThrow();
        assertThat(unchanged.getTripName()).isEqualTo("원래여행");
        assertThat(unchanged.getSequence()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trip_edit_command_records WHERE user_id = ?",
                Integer.class, ownerId)).isZero();
        assertThat(notificationCount("TRIP_EVENT")).isZero();
    }

    @Test
    void 실제_로그인_쿠키와_CSRF로_내부_명령을_처리한다() throws Exception {
        String sid = UUID.randomUUID().toString();
        String context = UUID.randomUUID().toString();
        sessions.save(sid, ownerId, UUID.randomUUID().toString());
        csrfTokens.save(context, "csrf-value");
        mvc.perform(post(path()).cookie(new Cookie("accessToken", tokens.issue(ownerId, sid)),
                        new Cookie("CSRF_CONTEXT", context))
                        .header("X-Internal-Service-Token", "edit-test-token")
                        .header("X-CSRF-TOKEN", "csrf-value").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"cookie\",\"changes\":{\"tripName\":\"쿠키편집\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
    }

    @Test
    void 공유_READ_접근_검사는_사용자와_유효기간을_반환한다() throws Exception {
        share(TripSharePermission.READ);
        mvc.perform(get("/internal/websocket/trips/" + trip.getId() + "/access")
                        .with(jwt().jwt(token -> token.subject(readerId.toString())))
                        .header("X-Internal-Service-Token", "edit-test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("TRIP_WEBSOCKET_ACCESS_FOUND"))
                .andExpect(jsonPath("$.data.userId").value(readerId))
                .andExpect(jsonPath("$.data.permission").value("READ"))
                .andExpect(jsonPath("$.data.accessValidUntil").isNotEmpty());
    }

    @Test
    void WRITE_일괄삭제와_재시도는_사진_통계를_한번만_변경한다() throws Exception {
        share(TripSharePermission.WRITE);
        stats.saveAndFlush(new UserStats(users.findById(ownerId).orElseThrow()));
        TripAttachment first = photo("first");
        TripAttachment second = photo("second");
        String body = "{\"requestId\":\"delete\",\"attachmentIds\":[" + first.getId() + "," + second.getId() + "]}";
        delete(readerId, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_PHOTOS_DELETE_SUCCESS"))
                .andExpect(jsonPath("$.data.sequence").value(1));
        delete(readerId, body).andExpect(status().isOk()).andExpect(jsonPath("$.data.sequence").value(1));
        assertThat(attachments.findById(first.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(files.findById(first.getFileId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(stats.findByUser_UserId(ownerId).orElseThrow().getStorageUsedBytes()).isZero();
        assertThat(notificationCount("TRIP_EVENT")).isEqualTo(1);
        String json = jdbc.queryForObject("SELECT event_json FROM trip_edit_notification_outbox WHERE target_type = 'TRIP' AND target_id = ?",
                String.class, trip.getId());
        assertThat(json).contains("TRIP_PHOTOS_DELETED", "\"attachmentCount\":0").doesNotContain("original/");
    }

    @Test
    void 중복_삭제_ID는_기록된_거절이며_사진은_유지한다() throws Exception {
        TripAttachment photo = photo("duplicate");
        delete(ownerId, "{\"requestId\":\"bad-delete\",\"attachmentIds\":[" + photo.getId() + "," + photo.getId() + "]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("INVALID_ATTACHMENT_IDS"));
        assertThat(attachments.findById(photo.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"%d.9", "\"%d\"", "9223372036854775808"})
    void 정수가_아닌_사진_ID는_삭제하지_않고_원문이_다르면_충돌한다(String idTemplate) throws Exception {
        TripAttachment photo = photo("invalid-id");
        stats.saveAndFlush(new UserStats(users.findById(ownerId).orElseThrow()));
        String invalidId = idTemplate.formatted(photo.getId());
        String invalidBody = "{\"requestId\":\"invalid-id\",\"attachmentIds\":[" + invalidId + "]}";

        delete(ownerId, invalidBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("INVALID_ATTACHMENT_IDS"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
        delete(ownerId, invalidBody).andExpect(jsonPath("$.data.status").value("REJECTED"));
        delete(ownerId, "{\"requestId\":\"invalid-id\",\"attachmentIds\":[" + photo.getId() + "]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("TRIP_EDIT_COMMAND_MISMATCH"));
        assertThat(attachments.findById(photo.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(trips.findById(trip.getId()).orElseThrow().getSequence()).isZero();
    }

    private void share(TripSharePermission permission) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        shares.saveAndFlush(new TripShareUser(trip.getId(), ownerId, readerId, permission, now.plusHours(1), now));
    }

    private TripAttachment photo(String key) {
        StoredFile file = StoredFile.uploaded(ownerId, key + ".jpg", "original/" + UUID.randomUUID(), "image/jpeg");
        file.storageSize(10L);
        files.saveAndFlush(file);
        TripAttachment photo = TripAttachment.initial(trip.getId(), file.getId(), "analyze/" + key, "preview/" + key);
        photo.classify(null, RegionOrigin.EXIF, LocalDateTime.now(), new BigDecimal("37.5"), new BigDecimal("127.0"), 90);
        return attachments.saveAndFlush(photo);
    }

    private int notificationCount(String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trip_edit_notification_outbox WHERE event_type = ? AND target_id = ?",
                Integer.class, type, "TRIP_EVENT".equals(type) ? trip.getId() : readerId);
    }

    private ResultActions delete(Long actorId, String body) throws Exception {
        return mvc.perform(post("/internal/websocket/trips/" + trip.getId() + "/attachments/delete")
                .with(jwt().jwt(token -> token.subject(actorId.toString()))).with(csrf())
                .header("X-Internal-Service-Token", "edit-test-token")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions update(Long actorId, String body) throws Exception {
        return mvc.perform(post(path()).with(jwt().jwt(token -> token.subject(actorId.toString()))).with(csrf())
                .header("X-Internal-Service-Token", "edit-test-token")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String path() {
        return "/internal/websocket/trips/" + trip.getId() + "/update";
    }
}
