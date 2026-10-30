package com.yeodam.websocket.trip.client;

import com.yeodam.websocket.common.websocket.config.BackendAuthenticationProperties;
import com.yeodam.websocket.common.websocket.security.WebSocketSessionCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TripPresenceAccessClientTest {
    private MockRestServiceServer server;
    private TripPresenceAccessClient client;
    private final WebSocketSessionCredentials credentials = new WebSocketSessionCredentials("access", "context");

    @BeforeEach
    void 테스트_준비() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://backend");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TripPresenceAccessClient(builder.build(), new BackendAuthenticationProperties(
                URI.create("http://backend"), "service-token", Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }

    @Test
    void 서비스_토큰과_사용자_쿠키를_보내고_검증된_권한을_반환한다() {
        server.expect(requestTo("http://backend/api/internal/websocket/trips/10/presence-access"))
                .andExpect(header("X-Internal-Service-Token", "service-token"))
                .andExpect(request -> assertThat(request.getHeaders().getFirst("Cookie"))
                        .contains("accessToken=access", "CSRF_CONTEXT=context"))
                .andRespond(withSuccess(권한_응답_본문_생성(42L, Instant.now().plusSeconds(20)), MediaType.APPLICATION_JSON));
        assertThat(client.findPresenceAccess(10L, 42L, credentials).permission()).isEqualTo("READ");
        server.verify();
    }

    @Test
    void 다른_사용자의_권한_응답은_거절한다() {
        server.expect(anything()).andRespond(withSuccess(권한_응답_본문_생성(99L, Instant.now().plusSeconds(20)),
                MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.findPresenceAccess(10L, 42L, credentials))
                .hasMessage("서버 내부 오류가 발생했습니다.");
    }

    @Test
    void 만료된_권한과_API_서버_실패는_접근을_허용하지_않는다() {
        server.expect(anything()).andRespond(withSuccess(권한_응답_본문_생성(42L, Instant.now().minusSeconds(1)),
                MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.findPresenceAccess(10L, 42L, credentials))
                .hasMessage("서버 내부 오류가 발생했습니다.");
        server.reset();
        server.expect(anything()).andRespond(withServerError());
        assertThatThrownBy(() -> client.findPresenceAccess(10L, 42L, credentials))
                .hasMessage("서버 내부 오류가 발생했습니다.");
    }

    private String 권한_응답_본문_생성(Long userId, Instant validUntil) {
        return """
                {"message":"TRIP_PRESENCE_ACCESS_FOUND","data":{
                "tripId":10,"userId":%d,"nickname":"지형","profileImageUrl":null,"permission":"READ",
                "checkedAt":"%s","accessValidUntil":"%s","shareExpiresAt":"%s"}}
                """.formatted(userId, Instant.now().minusSeconds(1), validUntil, Instant.now().plusSeconds(120));
    }
}
