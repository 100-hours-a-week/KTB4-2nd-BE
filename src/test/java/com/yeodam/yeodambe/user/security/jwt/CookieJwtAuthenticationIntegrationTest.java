package com.yeodam.yeodambe.user.security.jwt;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.security.session.IssuedLoginSession;
import com.yeodam.yeodambe.user.security.session.LoginSessionIssuer;
import com.yeodam.yeodambe.user.service.UserRegistrationService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, CookieJwtAuthenticationIntegrationTest.ProbeController.class})
class CookieJwtAuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRegistrationService userRegistrationService;
    @Autowired
    private LoginSessionIssuer loginSessionIssuer;
    @Autowired
    private AccessTokenIssuer accessTokenIssuer;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private TripShareUserRepository tripShareUserRepository;
    @Autowired
    private Clock clock;

    @Test
    void 공유_대상자_목록은_실제_로그인_쿠키로_소유자만_조회한다() throws Exception {
        User owner = registerShareUser("공유소유자");
        User grantee = registerShareUser("공유대상자");
        Trip trip = tripRepository.save(new Trip(
                owner.getUserId(), "공유여행", LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 11)));
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"));
        TripShareUser share = tripShareUserRepository.save(new TripShareUser(
                trip.getId(), owner.getUserId(), grantee.getUserId(), TripSharePermission.READ,
                now.plusHours(1), now));

        mockMvc.perform(get("/trips/{tripId}/shares", trip.getId()).cookie(loginCookie(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_SHARE_LIST_FOUND"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].tripShareId").value(share.getId()))
                .andExpect(jsonPath("$.data.items[0].granteeUserId").value(grantee.getUserId()))
                .andExpect(jsonPath("$.data.items[0].email").value(grantee.getEmail()))
                .andExpect(jsonPath("$.data.items[0].permission").value("READ"))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));

        mockMvc.perform(get("/trips/{tripId}/shares", trip.getId()).cookie(loginCookie(grantee)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("TRIP_NOT_FOUND"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void 공유_대상자가_없으면_실제_로그인_쿠키로_빈_목록을_반환한다() throws Exception {
        User owner = registerShareUser("공유소유자");
        Trip trip = tripRepository.save(new Trip(
                owner.getUserId(), "빈공유여행", LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 11)));

        mockMvc.perform(get("/trips/{tripId}/shares", trip.getId()).cookie(loginCookie(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_SHARE_LIST_FOUND"))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
    }

    @Test
    void 공유_대상자_목록은_없는_여행과_비소유자에게_404를_반환한다() throws Exception {
        User owner = registerShareUser("공유소유자");
        User other = registerShareUser("다른회원");
        Trip trip = tripRepository.save(new Trip(
                owner.getUserId(), "공유여행", LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 11)));
        Cookie ownerCookie = loginCookie(owner);

        mockMvc.perform(get("/trips/{tripId}/shares", Long.MAX_VALUE).cookie(ownerCookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("TRIP_NOT_FOUND"))
                .andExpect(jsonPath("$.data").value(nullValue()));
        mockMvc.perform(get("/trips/{tripId}/shares", trip.getId())
                        .cookie(loginCookie(other)).param("cursor", "broken!"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("TRIP_NOT_FOUND"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void 공유_대상자_목록은_쿠키가_없거나_잘못되면_거절한다() throws Exception {
        mockMvc.perform(get("/trips/{tripId}/shares", 1L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.data").value(nullValue()));
        mockMvc.perform(get("/trips/{tripId}/shares", 1L)
                        .cookie(new Cookie("accessToken", "invalid-jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void 공유_대상자_목록은_존재하지_않는_세션을_거절한다() throws Exception {
        User owner = registerShareUser("공유소유자");
        String accessToken = accessTokenIssuer.issue(owner.getUserId(), "missing-share-session");

        mockMvc.perform(get("/trips/{tripId}/shares", 1L)
                        .cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void 초대_목록은_실제_로그인_세션을_검증한다() throws Exception {
        User user = userRegistrationService.register(
                "invited-" + UUID.randomUUID() + "@yeodam.test", "초대회원",
                OAuthProvider.KAKAO, "kakao-invited-" + UUID.randomUUID());
        IssuedLoginSession session = loginSessionIssuer.issue(user.getUserId());
        String accessToken = accessTokenIssuer.issue(user.getUserId(), session.sid());

        mockMvc.perform(get("/trips/shares").cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("INVITED_TRIP_LIST_FOUND"))
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());

        String invalidSessionToken = accessTokenIssuer.issue(user.getUserId(), "missing-session");
        mockMvc.perform(get("/trips/shares").cookie(new Cookie("accessToken", invalidSessionToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));
    }

    @RestController
    static class ProbeController {
        @GetMapping("/test/auth-probe")
        String probe(@AuthenticationPrincipal Jwt jwt) {
            return jwt.getSubject();
        }
    }

    @Test
    void 유효한_액세스_쿠키로_보호된_요청을_인증한다() throws Exception {
        String unique = UUID.randomUUID().toString();
        User user = userRegistrationService.register(
                "auth-" + unique + "@yeodam.test",
                "인증회원",
                OAuthProvider.KAKAO,
                "kakao-auth-" + unique
        );
        IssuedLoginSession session = loginSessionIssuer.issue(user.getUserId());
        String accessToken = accessTokenIssuer.issue(user.getUserId(), session.sid());

        mockMvc.perform(get("/test/auth-probe")
                        .cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isOk())
                .andExpect(content().string(user.getUserId().toString()));
    }

    @Test
    void 새_회원의_통계를_실제_로그인_쿠키로_조회한다() throws Exception {
        String unique = UUID.randomUUID().toString();
        User user = userRegistrationService.register(
                "stats-" + unique + "@yeodam.test", "통계회원",
                OAuthProvider.KAKAO, "kakao-stats-" + unique);
        IssuedLoginSession session = loginSessionIssuer.issue(user.getUserId());
        String accessToken = accessTokenIssuer.issue(user.getUserId(), session.sid());

        mockMvc.perform(get("/users/me/stats")
                        .cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("USER_STATS_FOUND"))
                .andExpect(jsonPath("$.data.tripCount").value(0))
                .andExpect(jsonPath("$.data.attachmentCount").value(0))
                .andExpect(jsonPath("$.data.storyCount").value(0))
                .andExpect(jsonPath("$.data.storageUsedBytes").value(0))
                .andExpect(jsonPath("$.data.storageMaximumBytes").value(30_000_000_000L));
    }

    @Test
    void 통계_API는_인증_쿠키가_없거나_잘못되면_거부한다() throws Exception {
        mockMvc.perform(get("/users/me/stats"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));
        mockMvc.perform(get("/users/me/stats")
                        .cookie(new Cookie("accessToken", "invalid-jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));
    }

    @Test
    void 액세스_쿠키가_없거나_유효하지_않으면_보호된_요청을_거부한다() throws Exception {
        mockMvc.perform(get("/test/auth-probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("""
                        {
                          "message": "UNAUTHORIZED",
                          "data": null
                        }
                        """));

        mockMvc.perform(get("/test/auth-probe")
                        .cookie(new Cookie("accessToken", "invalid-jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("""
                        {
                          "message": "UNAUTHORIZED",
                          "data": null
                        }
                        """));
    }

    private User registerShareUser(String nickname) {
        String unique = UUID.randomUUID().toString();
        return userRegistrationService.register(
                "share-" + unique + "@yeodam.test", nickname, OAuthProvider.KAKAO, "kakao-share-" + unique);
    }

    private Cookie loginCookie(User user) {
        IssuedLoginSession session = loginSessionIssuer.issue(user.getUserId());
        String accessToken = accessTokenIssuer.issue(user.getUserId(), session.sid());
        return new Cookie("accessToken", accessToken);
    }
}
