package com.yeodam.websocket.trip.service;

import com.yeodam.websocket.common.websocket.config.WebSocketEventProperties;
import com.yeodam.websocket.common.websocket.security.WebSocketPrincipal;
import com.yeodam.websocket.common.websocket.security.WebSocketSessionCredentials;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import com.yeodam.websocket.trip.client.TripPresenceAccessClient;
import com.yeodam.websocket.trip.client.response.TripPresenceAccessResponse.AccessData;
import com.yeodam.websocket.trip.config.TripPresenceProperties;
import com.yeodam.websocket.trip.repository.TripPresenceRepository;
import com.yeodam.websocket.trip.repository.TripPresenceRepository.PresenceChange;
import com.yeodam.websocket.trip.service.response.TripPresenceSnapshotResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TripPresenceServiceTest {
    @Test
    void 미승인_여행의_퇴장은_Redis_상태를_생성하지_않는다() {
        TripPresenceSessionRegistry registry = new TripPresenceSessionRegistry();
        TripPresenceAccessClient accessClient = mock(TripPresenceAccessClient.class);
        TripPresenceRepository repository = mock(TripPresenceRepository.class);
        WebSocketSessionManager sessions = mock(WebSocketSessionManager.class);
        WebSocketSession session = mock(WebSocketSession.class);
        WebSocketPrincipal principal = new WebSocketPrincipal(42L, "login", Instant.now().plusSeconds(300));
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, List.of());
        when(session.getPrincipal()).thenReturn(authentication);
        when(session.isOpen()).thenReturn(true);
        when(sessions.findSession("socket")).thenReturn(session);
        TripPresenceService service = new TripPresenceService(registry, accessClient, repository, sessions,
                new TripPresenceProperties("test", 16, 100, 10), mock(StringRedisTemplate.class),
                new WebSocketEventProperties("events"), JsonMapper.builder().build(),
                mock(SimpMessagingTemplate.class), mock(MessageChannel.class), Runnable::run, Runnable::run);
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId("socket");
        headers.setUser(authentication);
        service.leave("999", MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()));
        verifyNoInteractions(repository);
    }

    @Test
    void 갱신_대상_연결은_개수_제한으로_누락하지_않는다() {
        TripPresenceSessionRegistry registry = new TripPresenceSessionRegistry();
        TripPresenceAccessClient accessClient = mock(TripPresenceAccessClient.class);
        TripPresenceRepository repository = mock(TripPresenceRepository.class);
        WebSocketSessionManager sessions = mock(WebSocketSessionManager.class);
        Instant now = Instant.now();
        WebSocketPrincipal principal = new WebSocketPrincipal(42L, "login", now.plusSeconds(300));
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, List.of());
        AccessData due = new AccessData(10L, 42L, "지형", null, "OWNER", now.minusSeconds(25),
                now.plusSeconds(5), null);
        AccessData fresh = new AccessData(10L, 42L, "지형", null, "OWNER", now, now.plusSeconds(30), null);
        for (int index = 0; index < 70; index++) {
            String connectionId = "socket-" + index;
            WebSocketSession session = mock(WebSocketSession.class);
            when(session.isOpen()).thenReturn(true);
            when(session.getPrincipal()).thenReturn(authentication);
            when(session.getAttributes()).thenReturn(new HashMap<>(java.util.Map.of(
                    WebSocketSessionCredentials.ATTRIBUTE_NAME, new WebSocketSessionCredentials("access", "context"))));
            when(sessions.findSession(connectionId)).thenReturn(session);
            registry.subscribe(registry.connection(connectionId), "sub", 10L, due);
        }
        when(accessClient.findPresenceAccess(eq(10L), eq(42L), any())).thenReturn(fresh);
        PresenceChange unchanged = new PresenceChange(false,
                new TripPresenceSnapshotResponse(10L, "epoch", 0L, List.of()));
        when(repository.refreshAccess(eq(10L), any())).thenReturn(unchanged);
        TripPresenceService service = new TripPresenceService(registry, accessClient, repository, sessions,
                new TripPresenceProperties("test", 16, 100, 10), mock(StringRedisTemplate.class),
                new WebSocketEventProperties("events"), JsonMapper.builder().build(),
                mock(SimpMessagingTemplate.class), mock(MessageChannel.class), Runnable::run, Runnable::run);
        service.revalidateAccess();
        verify(accessClient, times(70)).findPresenceAccess(eq(10L), eq(42L), any());
    }

    @Test
    void Redis_입장_중_종료되면_해당_연결만_제거한다() {
        TripPresenceSessionRegistry registry = new TripPresenceSessionRegistry();
        TripPresenceAccessClient accessClient = mock(TripPresenceAccessClient.class);
        TripPresenceRepository repository = mock(TripPresenceRepository.class);
        WebSocketSessionManager sessions = mock(WebSocketSessionManager.class);
        WebSocketSession session = mock(WebSocketSession.class);
        WebSocketPrincipal principal = new WebSocketPrincipal(42L, "login", Instant.now().plusSeconds(300));
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, List.of());
        when(session.getPrincipal()).thenReturn(authentication);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new HashMap<>(java.util.Map.of(
                WebSocketSessionCredentials.ATTRIBUTE_NAME, new WebSocketSessionCredentials("access", "context"))));
        when(sessions.findSession("socket")).thenReturn(session);
        Instant now = Instant.now();
        AccessData grant = new AccessData(10L, 42L, "지형", null, "OWNER", now, now.plusSeconds(30), null);
        registry.subscribe(registry.connection("socket"), "sub", 10L, grant);
        when(accessClient.findPresenceAccess(eq(10L), eq(42L), any())).thenReturn(grant);
        TripPresenceSnapshotResponse snapshot = new TripPresenceSnapshotResponse(10L, "epoch", 1L, List.of());
        when(repository.join(eq(10L), any())).thenAnswer(invocation -> {
            registry.close("socket");
            when(session.isOpen()).thenReturn(false);
            return new PresenceChange(false, snapshot);
        });
        when(repository.leave(eq(10L), anyString())).thenReturn(new PresenceChange(false, snapshot));
        TripPresenceService service = new TripPresenceService(registry, accessClient, repository, sessions,
                new TripPresenceProperties("test", 16, 100, 10), mock(StringRedisTemplate.class),
                new WebSocketEventProperties("events"), JsonMapper.builder().build(),
                mock(SimpMessagingTemplate.class), mock(MessageChannel.class), Runnable::run, Runnable::run);
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId("socket");
        headers.setUser(authentication);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        assertThatThrownBy(() -> service.join("10", message)).hasMessage("UNAUTHORIZED");
        verify(repository).leave(eq(10L), endsWith(":socket"));
    }
}
