package com.yeodam.websocket.trip.controller;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import com.yeodam.websocket.common.response.ErrorMessage;
import com.yeodam.websocket.common.websocket.security.WebSocketPrincipal;
import com.yeodam.websocket.common.websocket.security.WebSocketSessionCredentials;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import com.yeodam.websocket.trip.client.BackendTripEditClient;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.security.Principal;
import java.util.Map;
import java.util.Set;

@Controller
@RequiredArgsConstructor
@Slf4j
public class TripEditCommandController {

    private static final Set<ErrorMessage> REJECTION_MESSAGES = Set.of(
            ErrorMessage.TRIP_EDIT_COMMAND_MISMATCH,
            ErrorMessage.TRIP_EDIT_COMMAND_RESULT_EXPIRED,
            ErrorMessage.TRIP_NOT_FOUND,
            ErrorMessage.INVALID_TRIP_REQUEST,
            ErrorMessage.ATTACHMENT_NOT_FOUND,
            ErrorMessage.INVALID_REQUEST
    );
    private final BackendTripEditClient client;
    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper mapper;

    @MessageMapping("/trips/{tripId}/update")
    public void update(
            @DestinationVariable long tripId,
            byte[] payload,
            Principal principal,
            SimpMessageHeaderAccessor headers
    ) {
        execute(tripId, "update", payload, principal, headers);
    }

    @MessageMapping("/trips/{tripId}/attachments/delete")
    public void delete(
            @DestinationVariable long tripId,
            byte[] payload,
            Principal principal,
            SimpMessageHeaderAccessor headers
    ) {
        execute(tripId, "attachments/delete", payload, principal, headers);
    }

    private void execute(
            long tripId,
            String command,
            byte[] payload,
            Principal principal,
            SimpMessageHeaderAccessor headers
    ) {
        WebSocketPrincipal user = (WebSocketPrincipal) ((Authentication) principal).getPrincipal();
        JsonNode body;
        try {
            body = mapper.readTree(payload);
        } catch (RuntimeException exception) {
            reject(user, tripId, null, ErrorMessage.INVALID_TRIP_REQUEST);
            return;
        }
        String requestId = body == null ? null : body.path("requestId").asString(null);
        if (!validRequest(body, command)) {
            reject(user, tripId, requestId, ErrorMessage.INVALID_TRIP_REQUEST);
            return;
        }
        Map<String, Object> attributes = headers.getSessionAttributes();
        if (attributes == null
                || !(attributes.get(WebSocketSessionCredentials.ATTRIBUTE_NAME) instanceof WebSocketSessionCredentials credentials)
                || !(attributes.get(CsrfToken.class.getName()) instanceof CsrfToken csrfToken)) {
            sessionManager.closeConnection(headers.getSessionId());
            return;
        }
        try {
            client.execute(tripId, command, body, credentials, csrfToken.getToken());
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 401 || status == 403) {
                sessionManager.closeConnection(headers.getSessionId());
            } else if (status == 400 || status == 404 || status == 409 || status == 413 || status == 422) {
                reject(user, tripId, requestId, knownError(exception));
            } else {
                logPending(tripId);
            }
        } catch (RuntimeException exception) {
            logPending(tripId);
        }
    }

    private boolean validRequest(JsonNode body, String command) {
        if (body == null || !body.isObject() || !body.path("requestId").isTextual()
                || body.path("requestId").asString().isBlank() || body.path("requestId").asString().length() > 128
                || body.size() != 2) {
            return false;
        }
        if ("update".equals(command)) {
            return body.has("changes");
        }
        // 업무 입력 거절도 BE에 기록해야 같은 requestId로 다시 실행되지 않는다.
        return body.has("attachmentIds");
    }

    private ErrorMessage knownError(RestClientResponseException exception) {
        try {
            JsonNode body = mapper.readTree(exception.getResponseBodyAsByteArray());
            ErrorMessage message = ErrorMessage.valueOf(body.path("message").asString());
            if (REJECTION_MESSAGES.contains(message)) {
                return message;
            }
        } catch (RuntimeException ignored) {
            // Only known nonexecution HTTP errors reach this local rejection path.
        }
        return ErrorMessage.INVALID_TRIP_REQUEST;
    }

    private void reject(WebSocketPrincipal user, long tripId, String requestId, ErrorMessage message) {
        ObjectNode result = mapper.createObjectNode();
        result.put("message", message.name());
        ObjectNode data = result.putObject("data");
        if (requestId != null && requestId.length() <= 128) {
            data.put("requestId", requestId);
        }
        data.put("tripId", tripId);
        data.put("status", "REJECTED");
        messagingTemplate.convertAndSendToUser(user.getName(), "/queue/command-results", result);
    }

    private void logPending(long tripId) {
        log.atWarn().addKeyValue("event", "trip_edit_command_pending")
                .addKeyValue("trip_id", tripId)
                .log("Command outcome is unknown; retry with the same requestId.");
    }
}
