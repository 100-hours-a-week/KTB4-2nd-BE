package com.yeodam.websocket.common.websocket.session;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import com.yeodam.websocket.common.websocket.client.BackendAuthenticationClient;
import com.yeodam.websocket.common.websocket.exception.BackendAuthenticationException;
import com.yeodam.websocket.common.websocket.exception.BackendAuthenticationFailure;
import com.yeodam.websocket.common.websocket.security.WebSocketPrincipal;
import com.yeodam.websocket.common.websocket.security.WebSocketSessionCredentials;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebSocketSessionRevalidationService {

    private final WebSocketSessionManager sessionManager;
    private final BackendAuthenticationClient authenticationClient;

    @Scheduled(
            scheduler = "webSocketSessionRevalidationTaskScheduler",
            initialDelay = 30_000,
            fixedDelay = 30_000
    )
    public void revalidateSessions() {
        for (List<WebSocketSession> connections :
                sessionManager.getSessionsByLoginSession().values()) {
            revalidateGroup(connections);
        }
    }

    private void revalidateGroup(List<WebSocketSession> connections) {
        WebSocketPrincipal selectedPrincipal = null;
        WebSocketSessionCredentials selectedCredentials = null;

        for (WebSocketSession connection : connections) {
            Object value = connection.getAttributes()
                    .get(WebSocketSessionCredentials.ATTRIBUTE_NAME);

            if (!(value instanceof WebSocketSessionCredentials credentials)) {
                sessionManager.closeConnection(connection.getId());
                continue;
            }

            Authentication authentication =
                    (Authentication) connection.getPrincipal();

            WebSocketPrincipal principal =
                    (WebSocketPrincipal) authentication.getPrincipal();

            if (selectedPrincipal == null
                    || principal.accessTokenExpiresAt()
                    .isAfter(selectedPrincipal.accessTokenExpiresAt())) {
                selectedPrincipal = principal;
                selectedCredentials = credentials;
            }
        }

        if (selectedPrincipal == null) {
            return;
        }

        try {
            var result = authenticationClient.authenticate(
                    selectedCredentials.accessToken(),
                    selectedCredentials.browserContext()
            );

            if (!selectedPrincipal.userId().equals(result.userId())
                    || !selectedPrincipal.sessionId().equals(result.sessionId())) {
                throw new BackendAuthenticationException(
                        BackendAuthenticationFailure.INVALID_RESPONSE
                );
            }
        } catch (BackendAuthenticationException exception) {
            log.atWarn().addKeyValue("event", "websocket_session_revalidation")
                    .addKeyValue("result", "failure")
                    .addKeyValue("failure", exception.failure().name())
                    .log("WebSocket login session revalidation rejected.");

            for (WebSocketSession connection : connections) {
                sessionManager.closeConnection(connection.getId());
            }
        }
    }
}
