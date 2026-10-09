package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.entity.TripRegion;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.session.IssuedLoginSession;
import com.yeodam.yeodambe.user.security.session.LoginSessionIssuer;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import com.yeodam.yeodambe.user.security.TokenHasher;
import com.yeodam.yeodambe.user.service.UserRegistrationService;
import com.yeodam.yeodambe.user.service.WithdrawalService;
import com.yeodam.yeodambe.common.exception.WithdrawalFailedException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.http.HttpHeaders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import org.springframework.dao.DataAccessResourceFailureException;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class WithdrawalIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRegistrationService userRegistrationService;
    @Autowired
    private LoginSessionIssuer loginSessionIssuer;
    @MockitoSpyBean
    private LoginSessionStore loginSessionStore;
    @Autowired
    private TokenHasher tokenHasher;
    @Autowired
    private AccessTokenIssuer accessTokenIssuer;
    @MockitoSpyBean
    private CsrfTokenStore csrfTokenStore;
    @Autowired
    private WithdrawalService withdrawalService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private TripRegionRepository tripRegionRepository;
    @Autowired
    private TripDetailPlaceRepository tripDetailPlaceRepository;
    @Autowired
    private TripAttachmentRepository tripAttachmentRepository;
    @Autowired
    private StoredFileRepository storedFileRepository;

    @Test
    void 탈퇴하면_회원과_여행을_소프트_삭제하고_세션을_제거하며_액세스_토큰을_무효화한다()
            throws Exception {
        String unique = UUID.randomUUID().toString();
        User user = userRegistrationService.register(
                "withdrawal-" + unique + "@yeodam.test",
                "탈퇴회원",
                OAuthProvider.KAKAO,
                "kakao-withdrawal-" + unique
        );
        IssuedLoginSession session = loginSessionIssuer.issue(user.getUserId());
        String accessToken = accessTokenIssuer.issue(user.getUserId(), session.sid());
        String browserContext = "withdrawal-browser-" + unique;
        csrfTokenStore.save(browserContext, "withdrawal-csrf-token");
        Trip trip = tripRepository.saveAndFlush(new Trip(
                user.getUserId(), "제주 여행", LocalDate.now(), LocalDate.now()));
        TripRegion region = tripRegionRepository.saveAndFlush(new TripRegion(
                trip,
                "50110",
                "제주특별자치도 제주시",
                new BigDecimal("33.5"),
                new BigDecimal("126.5")
        ));
        TripDetailPlace place = tripDetailPlaceRepository.saveAndFlush(
                TripDetailPlace.fromAnalysis(
                        trip.getId(),
                        1,
                        "제주시",
                        new BigDecimal("33.5"),
                        new BigDecimal("126.5"),
                        LocalDateTime.now(),
                        LocalDateTime.now(),
                        "preview-key"
                )
        );
        StoredFile file = storedFileRepository.saveAndFlush(StoredFile.uploaded(
                user.getUserId(),
                "photo.jpg",
                "original-key",
                "image/jpeg"
        ));
        TripAttachment attachment = tripAttachmentRepository.saveAndFlush(
                TripAttachment.initial(
                        trip.getId(),
                        file.getId(),
                        "analyze-key",
                        "preview-key"
                )
        );

        var response = mockMvc.perform(delete("/users/me")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", browserContext)
                        )
                        .header("X-CSRF-TOKEN", "withdrawal-csrf-token"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse();

        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .hasSize(2)
                .anySatisfy(cookie -> assertThat(cookie)
                        .contains("accessToken=;", "Path=/;", "Max-Age=0", "HttpOnly", "SameSite=Lax"))
                .anySatisfy(cookie -> assertThat(cookie)
                        .contains("refreshToken=;", "Path=/api/auth;", "Max-Age=0", "HttpOnly", "SameSite=Lax"));
        assertThat(csrfTokenStore.find(browserContext)).isNull();

        assertSoftDeleted("users", "user_id", user.getUserId());
        assertSoftDeleted("oauth_accounts", "user_id", user.getUserId());
        assertSoftDeleted("consents", "user_id", user.getUserId());
        assertSoftDeleted("user_stats", "user_id", user.getUserId());
        assertSoftDeleted("trips", "trip_id", trip.getId());
        assertSoftDeleted("trip_regions", "region_id", region.getId());
        assertSoftDeleted("trip_detail_places", "trip_place_id", place.getId());
        assertSoftDeleted("trip_attachments", "trip_attachment_id", attachment.getId());
        assertSoftDeleted("files", "file_id", file.getId());
        assertThat(loginSessionStore.findBySid(session.sid())).isEmpty();
        assertThat(loginSessionStore.findSidByRefreshTokenHash(tokenHasher.hash(session.refreshToken()))).isEmpty();

        mockMvc.perform(get("/users/me")
                        .cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("""
                        {
                          "message": "UNAUTHORIZED",
                          "data": null
                        }
                        """));
    }

    @Test
    void CSRF_토큰이_없는_탈퇴는_회원_데이터를_변경하지_않는다() throws Exception {
        String unique = UUID.randomUUID().toString();
        User user = userRegistrationService.register(
                "csrf-" + unique + "@yeodam.test",
                "회원보호",
                OAuthProvider.KAKAO,
                "kakao-csrf-" + unique
        );
        IssuedLoginSession session = loginSessionIssuer.issue(user.getUserId());
        String accessToken = accessTokenIssuer.issue(user.getUserId(), session.sid());

        mockMvc.perform(delete("/users/me")
                        .cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isForbidden())
                .andExpect(content().json("""
                        {
                          "message": "CSRF_TOKEN_INVALID",
                          "data": null
                        }
                        """));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM users WHERE user_id = ?",
                Object.class,
                user.getUserId()
        )).isNull();
    }

    @Test
    void 커밋_후_CSRF_정리가_실패해도_탈퇴를_완료하고_세션을_삭제한다() {
        String unique = UUID.randomUUID().toString();
        User user = userRegistrationService.register(
                "rollback-" + unique + "@yeodam.test", "롤백회원",
                OAuthProvider.KAKAO, "kakao-rollback-" + unique);
        IssuedLoginSession session = loginSessionIssuer.issue(user.getUserId());
        String browserContext = "rollback-browser-" + unique;
        csrfTokenStore.save(browserContext, "rollback-csrf-token");
        Trip trip = tripRepository.saveAndFlush(new Trip(
                user.getUserId(), "롤백 여행", LocalDate.now(), LocalDate.now()));

        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Simulated failure after CSRF deletion");
        }).when(csrfTokenStore).delete(browserContext);

        withdrawalService.withdraw(user.getUserId(), browserContext);

        for (String table : new String[]{"users", "oauth_accounts", "consents", "user_stats"}) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT deleted_at FROM " + table + " WHERE user_id = ?",
                    Object.class, user.getUserId())).isNotNull();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM trips WHERE trip_id = ?",
                Object.class, trip.getId())).isNotNull();
        assertThat(loginSessionStore.findBySid(session.sid())).isEmpty();
        assertThat(loginSessionStore.findSidByRefreshTokenHash(tokenHasher.hash(session.refreshToken()))).isEmpty();
        assertThat(csrfTokenStore.find(browserContext)).isNull();
    }

    @Test
    void 커밋_후_세션_정리가_실패해도_탈퇴를_완료하고_접근을_거부한다() throws Exception {
        String unique = UUID.randomUUID().toString();
        User user = userRegistrationService.register(unique + "@yeodam.test", "정리실패", OAuthProvider.KAKAO, unique);
        IssuedLoginSession session = loginSessionIssuer.issue(user.getUserId());
        String access = accessTokenIssuer.issue(user.getUserId(), session.sid());
        String browser = "cleanup-failure-" + unique;
        csrfTokenStore.save(browser, "cleanup-csrf");
        doThrow(new DataAccessResourceFailureException("Simulated failure with private-token-value"))
                .when(loginSessionStore).deleteByUserId(user.getUserId());
        Logger logger = (Logger) LoggerFactory.getLogger(WithdrawalService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            mockMvc.perform(delete("/users/me")
                            .cookie(new Cookie("accessToken", access), new Cookie("CSRF_CONTEXT", browser))
                            .header("X-CSRF-TOKEN", "cleanup-csrf"))
                    .andExpect(status().isNoContent());
            assertSoftDeleted("users", "user_id", user.getUserId());
            assertThat(csrfTokenStore.find(browser)).isNull();
            assertThat(loginSessionStore.findBySid(session.sid())).isPresent();
            mockMvc.perform(get("/users/me").cookie(new Cookie("accessToken", access)))
                    .andExpect(status().isUnauthorized());
            assertThat(logs.list).anySatisfy(event -> {
                assertThat(event.getKeyValuePairs()).anySatisfy(pair -> {
                    assertThat(pair.key).isEqualTo("event");
                    assertThat(pair.value).isEqualTo("auth_session_withdrawal_cleanup");
                });
                assertThat(event.getFormattedMessage()).doesNotContain("private-token-value", access, session.sid());
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    private void assertSoftDeleted(String tableName, String idColumn, Long id) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM " + tableName + " WHERE " + idColumn + " = ?",
                Object.class,
                id
        )).isNotNull();
    }
}
