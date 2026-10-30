package com.yeodam.websocket.trip;

import com.yeodam.websocket.RedisTestSupport;
import com.yeodam.websocket.YeodamWebSocketApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import com.yeodam.websocket.common.websocket.client.BackendAuthenticationClient;
import com.yeodam.websocket.common.websocket.client.response.BackendAuthenticationResponse.AuthenticationData;
import com.yeodam.websocket.trip.client.TripPresenceAccessClient;
import com.yeodam.websocket.trip.client.response.TripPresenceAccessResponse.AccessData;
import com.yeodam.websocket.trip.exception.TripPresenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "websocket.backend-auth.service-token=presence-test")
class TripPresenceMessagingTest extends RedisTestSupport {
    @LocalServerPort
    private int port;
    @MockitoBean
    private BackendAuthenticationClient authenticationClient;
    @MockitoBean
    private TripPresenceAccessClient accessClient;
    private final List<WebSocket> sockets = new ArrayList<>();

    @AfterEach
    void 소켓_종료() {
        sockets.forEach(WebSocket::abort);
    }

    @Test
    void 구독_직후_입장과_중복_입장_모두_현재_목록을_받는다() throws Exception {
        Frames frames = 인증된_소켓_연결();
        프레임_전송(frames.socket, "SUBSCRIBE\nid:p\ndestination:/topic/trips/701/presence\n\n");
        프레임_전송(frames.socket, "SEND\ndestination:/app/trips/701/presence/join\n\n");
        String first = frames.다음_프레임_수신();
        assertThat(first).startsWith("MESSAGE").contains("PRESENCE_SNAPSHOT", "지형", "epoch");
        프레임_전송(frames.socket, "SEND\ndestination:/app/trips/701/presence/join\n\n");
        assertThat(frames.다음_프레임_수신()).contains("PRESENCE_SNAPSHOT");
    }

    @Test
    void 미승인_구독과_입장_전_체류_갱신은_거절한다() throws Exception {
        Frames frames = 인증된_소켓_연결();
        when(accessClient.findPresenceAccess(eq(702L), anyLong(), any()))
                .thenThrow(new TripPresenceException("TRIP_NOT_FOUND"));
        프레임_전송(frames.socket, "SUBSCRIBE\nid:p\ndestination:/topic/trips/702/presence\n\n");
        assertThat(frames.다음_프레임_수신()).startsWith("ERROR").contains("TRIP_NOT_FOUND");

        Frames second = 인증된_소켓_연결();
        프레임_전송(second.socket, "SUBSCRIBE\nid:p\ndestination:/topic/trips/703/presence\n\n");
        프레임_전송(second.socket, "SEND\ndestination:/app/trips/703/presence/heartbeat\n\n");
        assertThat(second.다음_프레임_수신()).startsWith("ERROR").contains("PRESENCE_NOT_JOINED");
    }

    @Test
    void 본문이_있는_명령과_브로커_직접_전송은_거절한다() throws Exception {
        Frames frames = 인증된_소켓_연결();
        프레임_전송(frames.socket, "SUBSCRIBE\nid:p\ndestination:/topic/trips/704/presence\n\n");
        프레임_전송(frames.socket, "SEND\ndestination:/app/trips/704/presence/join\n\n{}");
        assertThat(frames.다음_프레임_수신()).startsWith("ERROR").contains("INVALID_REQUEST");
        Frames second = 인증된_소켓_연결();
        프레임_전송(second.socket, "SEND\ndestination:/topic/trips/704/presence\n\n{}");
        assertThat(second.다음_프레임_수신()).startsWith("ERROR");
    }

    @Test
    void 두_WS_서버가_참여_목록을_공유하고_퇴장을_전파한다() throws Exception {
        Frames first = 인증된_소켓_연결();
        프레임_전송(first.socket, "SUBSCRIBE\nid:p\ndestination:/topic/trips/705/presence\n\n");
        프레임_전송(first.socket, "SEND\ndestination:/app/trips/705/presence/join\n\n");
        assertThat(first.다음_프레임_수신()).contains("PRESENCE_SNAPSHOT", "지형");
        try (ConfigurableApplicationContext secondContext = new SpringApplicationBuilder(
                YeodamWebSocketApplication.class, SecondAppMocks.class).run(
                        "--server.port=0", "--spring.data.redis.host=" + redisHost(),
                        "--spring.data.redis.port=" + redisPort(),
                        "--websocket.backend-auth.service-token=presence-test")) {
            BackendAuthenticationClient secondAuthentication = secondContext.getBean(
                    "두번째_서버_인증_클라이언트", BackendAuthenticationClient.class);
            TripPresenceAccessClient secondAccess = secondContext.getBean("두번째_서버_권한_클라이언트", TripPresenceAccessClient.class);
            when(secondAuthentication.authenticate("test-access", "test-context")).thenReturn(
                    new AuthenticationData(43L, "second-login", Instant.now().plusSeconds(300), "csrf"));
            when(secondAccess.findPresenceAccess(anyLong(), eq(43L), any())).thenAnswer(invocation -> {
                Instant now = Instant.now();
                return new AccessData(invocation.getArgument(0), 43L, "민수", null, "READ", now,
                        now.plusSeconds(30), now.plusSeconds(300));
            });
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                    secondContext.getBean(com.yeodam.websocket.common.websocket.service.RedisSubscriptionState.class).isReady());
            int secondPort = ((ServletWebServerApplicationContext) secondContext).getWebServer().getPort();
            Frames second = 지정한_포트로_소켓_연결(secondPort);
            프레임_전송(second.socket, "SUBSCRIBE\nid:p\ndestination:/topic/trips/705/presence\n\n");
            프레임_전송(second.socket, "SEND\ndestination:/app/trips/705/presence/join\n\n");
            assertThat(second.다음_프레임_수신()).contains("지형", "민수");
            프레임_전송(first.socket, "SEND\ndestination:/app/trips/705/presence/leave\n\n");
            boolean left = false;
            for (int attempt = 0; attempt < 5 && !left; attempt++) {
                String frame = second.다음_프레임_수신();
                left = frame.contains("민수") && !frame.contains("지형");
            }
            assertThat(left).isTrue();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecondAppMocks {
        @Bean
        @Primary
        BackendAuthenticationClient 두번째_서버_인증_클라이언트() {
            return mock(BackendAuthenticationClient.class);
        }

        @Bean
        @Primary
        TripPresenceAccessClient 두번째_서버_권한_클라이언트() {
            return mock(TripPresenceAccessClient.class);
        }
    }

    private Frames 인증된_소켓_연결() throws Exception {
        when(authenticationClient.authenticate("test-access", "test-context"))
                .thenReturn(new AuthenticationData(42L, "login", Instant.now().plusSeconds(300), "csrf"));
        when(accessClient.findPresenceAccess(anyLong(), eq(42L), any()))
                .thenAnswer(invocation -> {
                    Instant now = Instant.now();
                    return new AccessData(invocation.getArgument(0), 42L, "지형", null, "OWNER", now,
                            now.plusSeconds(30), null);
                });
        return 지정한_포트로_소켓_연결(port);
    }

    private Frames 지정한_포트로_소켓_연결(int targetPort) throws Exception {
        Frames frames = new Frames();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", "http://localhost:5173")
                .header("Cookie", "accessToken=test-access; CSRF_CONTEXT=test-context")
                .subprotocols("v12.stomp")
                .buildAsync(URI.create("ws://localhost:" + targetPort + "/api/ws"), frames).join();
        frames.socket = socket;
        sockets.add(socket);
        프레임_전송(socket, "CONNECT\naccept-version:1.2\nheart-beat:0,0\nX-CSRF-TOKEN:csrf\n\n");
        assertThat(frames.다음_프레임_수신()).startsWith("CONNECTED");
        return frames;
    }

    private void 프레임_전송(WebSocket socket, String frame) {
        socket.sendText(frame + "\u0000", true).join();
    }

    private static final class Frames implements WebSocket.Listener {
        private WebSocket socket;
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletableFuture<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        private String 다음_프레임_수신() throws Exception {
            String frame = messages.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("STOMP response").isNotNull();
            return frame;
        }
    }
}
