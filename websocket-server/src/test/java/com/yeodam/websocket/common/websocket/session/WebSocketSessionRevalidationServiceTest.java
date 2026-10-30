package com.yeodam.websocket.common.websocket.session;

import com.yeodam.websocket.common.websocket.client.BackendAuthenticationClient;
import com.yeodam.websocket.common.websocket.client.response.BackendAuthenticationResponse.AuthenticationData;
import com.yeodam.websocket.common.websocket.exception.BackendAuthenticationException;
import com.yeodam.websocket.common.websocket.exception.BackendAuthenticationFailure;
import com.yeodam.websocket.common.websocket.security.WebSocketPrincipal;
import com.yeodam.websocket.common.websocket.security.WebSocketSessionCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

class WebSocketSessionRevalidationServiceTest {

    private final WebSocketSessionManager manager = mock(WebSocketSessionManager.class);
    private final BackendAuthenticationClient client = mock(BackendAuthenticationClient.class);
    private final WebSocketSessionRevalidationService service =
            new WebSocketSessionRevalidationService(manager, client);

    @Test
    void 같은_로그인_세션은_최신_토큰으로_한_번만_검사한다() {
        var old = connection("old", "login-a", 1);
        var recent = connection("recent", "login-a", 2);
        when(manager.getSessionsByLoginSession()).thenReturn(Map.of("login-a", List.of(old, recent)));
        when(client.authenticate("recent-token", "context")).thenReturn(valid("login-a"));

        service.revalidateSessions();

        verify(client).authenticate("recent-token", "context");
        verifyNoMoreInteractions(client);
        verify(manager, never()).closeConnection(anyString());
    }

    @ParameterizedTest
    @EnumSource(BackendAuthenticationFailure.class)
    void 실패한_로그인_그룹만_닫고_나머지_검사를_계속한다(BackendAuthenticationFailure failure) {
        var first = connection("first", "login-a", 1);
        var second = connection("second", "login-a", 2);
        var other = connection("other", "login-b", 1);
        Map<String, List<WebSocketSession>> groups = new java.util.LinkedHashMap<>();
        groups.put("login-a", List.of(first, second));
        groups.put("login-b", List.of(other));
        when(manager.getSessionsByLoginSession()).thenReturn(groups);
        when(client.authenticate("second-token", "context"))
                .thenThrow(new BackendAuthenticationException(failure));
        when(client.authenticate("other-token", "context")).thenReturn(valid("login-b"));

        service.revalidateSessions();

        verify(manager).closeConnection("first");
        verify(manager).closeConnection("second");
        verify(manager, never()).closeConnection("other");
        verify(client).authenticate("other-token", "context");
    }

    @Test
    void 다른_사용자의_응답을_거절한다() {
        assertIdentityRejected(new AuthenticationData(99L, "login-a", expiry(2), "csrf"));
    }

    @Test
    void 다른_로그인_세션의_응답을_거절한다() {
        assertIdentityRejected(valid("another-login"));
    }

    @Test
    void 자격_증명이_없는_연결은_닫고_나머지_연결을_검사한다() {
        var missing = connection("missing", "login-a", 2);
        missing.getAttributes().clear();
        var valid = connection("valid", "login-a", 1);
        when(manager.getSessionsByLoginSession()).thenReturn(Map.of("login-a", List.of(missing, valid)));
        when(client.authenticate("valid-token", "context")).thenReturn(valid("login-a"));

        service.revalidateSessions();

        verify(manager).closeConnection("missing");
        verify(manager, never()).closeConnection("valid");
        verify(client).authenticate("valid-token", "context");
    }

    @Test
    void 자격_증명이_있는_연결이_없으면_BE를_호출하지_않는다() {
        var missing = connection("missing", "login-a", 1);
        missing.getAttributes().clear();
        when(manager.getSessionsByLoginSession()).thenReturn(Map.of("login-a", List.of(missing)));

        service.revalidateSessions();

        verify(manager).closeConnection("missing");
        verifyNoInteractions(client);
    }

    @Test
    void 연결이_없으면_BE를_호출하지_않는다() {
        when(manager.getSessionsByLoginSession()).thenReturn(Map.of());

        service.revalidateSessions();

        verifyNoInteractions(client);
    }

    private void assertIdentityRejected(AuthenticationData response) {
        var connection = connection("first", "login-a", 1);
        when(manager.getSessionsByLoginSession()).thenReturn(Map.of("login-a", List.of(connection)));
        when(client.authenticate("first-token", "context")).thenReturn(response);

        service.revalidateSessions();

        verify(manager).closeConnection("first");
    }

    private WebSocketSession connection(String id, String loginId, int expiryOffset) {
        WebSocketSession connection = mock(WebSocketSession.class);
        when(connection.getId()).thenReturn(id);
        when(connection.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                new WebSocketPrincipal(42L, loginId, expiry(expiryOffset)), null, List.of()
        ));
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WebSocketSessionCredentials.ATTRIBUTE_NAME,
                new WebSocketSessionCredentials(id + "-token", "context"));
        when(connection.getAttributes()).thenReturn(attributes);
        return connection;
    }

    private AuthenticationData valid(String loginId) {
        return new AuthenticationData(42L, loginId, expiry(2), "csrf");
    }

    private Instant expiry(int offset) {
        return Instant.parse("2099-01-01T00:00:00Z").plusSeconds(offset);
    }
}
