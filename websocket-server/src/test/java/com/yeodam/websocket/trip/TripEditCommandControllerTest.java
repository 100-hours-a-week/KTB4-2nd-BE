package com.yeodam.websocket.trip;

import com.yeodam.websocket.common.websocket.security.WebSocketPrincipal;
import com.yeodam.websocket.common.websocket.security.WebSocketSessionCredentials;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import com.yeodam.websocket.trip.client.BackendTripEditClient;
import com.yeodam.websocket.trip.controller.TripEditCommandController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TripEditCommandControllerTest {

    private final BackendTripEditClient client = mock(BackendTripEditClient.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final WebSocketSessionManager sessions = mock(WebSocketSessionManager.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TripEditCommandController controller = new TripEditCommandController(client, messaging, sessions, mapper);
    private final WebSocketSessionCredentials credentials = new WebSocketSessionCredentials("access", "context");
    private final Authentication principal = UsernamePasswordAuthenticationToken.authenticated(
            new WebSocketPrincipal(42L, "login", Instant.now().plusSeconds(300)), null, List.of()
    );

    @Test
    void 서버_신원과_원래_CSRF로_수정을_전달하고_성공을_직접_보내지_않는다() {
        byte[] payload = bytes("{\"requestId\":\"r1\",\"changes\":{\"tripName\":\"Jeju\"}}");
        controller.update(14, payload, principal, headers());
        verify(client).execute(eq(14L), eq("update"), eq(mapper.readTree(payload)), same(credentials), eq("original-csrf"));
        verifyNoInteractions(messaging, sessions);
    }

    @Test
    void 사진_단건과_일괄_삭제를_전달한다() {
        controller.delete(14, bytes("{\"requestId\":\"r1\",\"attachmentIds\":[10,20]}"), principal, headers());
        verify(client).execute(eq(14L), eq("attachments/delete"), any(), same(credentials), eq("original-csrf"));
        verifyNoInteractions(messaging, sessions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "not-json", "null",
            "{\"requestId\":\"r1\",\"changes\":{\"tripName\":\"Jeju\"},\"userId\":99}"})
    void 잘못된_프로토콜은_BE_호출이나_연결_종료_없이_거절한다(String json) {
        controller.update(14, bytes(json), principal, headers());
        verifyNoInteractions(client, sessions);
        ArgumentCaptor<JsonNode> result = ArgumentCaptor.forClass(JsonNode.class);
        verify(messaging).convertAndSendToUser(eq("42"), eq("/queue/command-results"), result.capture());
        assertThat(result.getValue().path("message").asString()).isEqualTo("INVALID_TRIP_REQUEST");
        assertThat(result.getValue().path("data").path("status").asString()).isEqualTo("REJECTED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"userId\":99}", "{\"startDate\":\"2026-10-01\"}"})
    void 업무상_잘못된_수정값도_영속_거절_기록을_위해_전달한다(String changes) {
        controller.update(14, bytes("{\"requestId\":\"r1\",\"changes\":" + changes + "}"), principal, headers());
        verify(client).execute(eq(14L), eq("update"), any(), same(credentials), eq("original-csrf"));
        verifyNoInteractions(messaging, sessions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[0]", "[-1]", "[1,1]", "[null]"})
    void 업무상_잘못된_삭제_ID도_영속_거절_기록을_위해_전달한다(String ids) {
        controller.delete(14, bytes("{\"requestId\":\"r1\",\"attachmentIds\":" + ids + "}"), principal, headers());
        verify(client).execute(eq(14L), eq("attachments/delete"), any(), same(credentials), eq("original-csrf"));
        verifyNoInteractions(messaging, sessions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\"1\"]", "[1.9]", "[9223372036854775808]", "{}", "null"})
    void 잘못된_삭제_JSON도_BE가_원문을_기록하도록_전달한다(String ids) {
        controller.delete(14, bytes("{\"requestId\":\"r1\",\"attachmentIds\":" + ids + "}"), principal, headers());
        verify(client).execute(eq(14L), eq("attachments/delete"),
                eq(mapper.readTree("{\"requestId\":\"r1\",\"attachmentIds\":" + ids + "}")),
                same(credentials), eq("original-csrf"));
        verifyNoInteractions(messaging, sessions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "server", "invalid"})
    void 결과가_불명확하면_거절_결과를_만들지_않는다(String failure) {
        RuntimeException exception = switch (failure) {
            case "timeout" -> new ResourceAccessException("timeout");
            case "server" -> new HttpServerErrorException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
            default -> new org.springframework.web.client.RestClientException("invalid");
        };
        doThrow(exception).when(client).execute(anyLong(), anyString(), any(), any(), anyString());
        controller.update(14, bytes("{\"requestId\":\"r1\",\"changes\":{\"tripName\":\"Jeju\"}}"), principal, headers());
        verifyNoInteractions(messaging, sessions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRIP_EDIT_COMMAND_MISMATCH", "TRIP_EDIT_COMMAND_RESULT_EXPIRED",
            "TRIP_NOT_FOUND", "INVALID_TRIP_REQUEST", "ATTACHMENT_NOT_FOUND", "INVALID_REQUEST"})
    void 허용된_미실행_오류_코드를_유지한다(String message) {
        HttpClientErrorException exception = HttpClientErrorException.create(org.springframework.http.HttpStatus.CONFLICT,
                "Conflict", HttpHeaders.EMPTY, bytes("{\"message\":\"" + message + "\",\"data\":null}"), StandardCharsets.UTF_8);
        doThrow(exception).when(client).execute(anyLong(), anyString(), any(), any(), anyString());
        controller.update(14, bytes("{\"requestId\":\"r1\",\"changes\":{\"tripName\":\"Jeju\"}}"), principal, headers());
        ArgumentCaptor<JsonNode> result = ArgumentCaptor.forClass(JsonNode.class);
        verify(messaging).convertAndSendToUser(eq("42"), eq("/queue/command-results"), result.capture());
        assertThat(result.getValue().path("message").asString()).isEqualTo(message);
        assertThat(result.getValue().path("data").path("requestId").asString()).isEqualTo("r1");
        verifyNoInteractions(sessions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{}", "{\"message\":\"UNKNOWN\"}",
            "{\"message\":\"FORBIDDEN\"}", "{\"message\":\"CSRF_TOKEN_INVALID\"}"})
    void 알_수_없거나_허용하지_않은_BE_오류_코드는_그대로_전달하지_않는다(String response) {
        HttpClientErrorException exception = HttpClientErrorException.create(org.springframework.http.HttpStatus.BAD_REQUEST,
                "Bad Request", HttpHeaders.EMPTY, bytes(response), StandardCharsets.UTF_8);
        doThrow(exception).when(client).execute(anyLong(), anyString(), any(), any(), anyString());

        controller.update(14, bytes("{\"requestId\":\"r1\",\"changes\":{\"tripName\":\"Jeju\"}}"), principal, headers());

        ArgumentCaptor<JsonNode> result = ArgumentCaptor.forClass(JsonNode.class);
        verify(messaging).convertAndSendToUser(eq("42"), eq("/queue/command-results"), result.capture());
        assertThat(result.getValue().path("message").asString()).isEqualTo("INVALID_TRIP_REQUEST");
        assertThat(result.getValue().path("data").path("requestId").asString()).isEqualTo("r1");
        verifyNoInteractions(sessions);
    }

    @Test
    void 인증_실패는_업무_거절_없이_연결을_닫는다() {
        doThrow(new HttpClientErrorException(org.springframework.http.HttpStatus.UNAUTHORIZED))
                .when(client).execute(anyLong(), anyString(), any(), any(), anyString());
        controller.update(14, bytes("{\"requestId\":\"r1\",\"changes\":{\"tripName\":\"Jeju\"}}"), principal, headers());
        verify(sessions).closeConnection("s1");
        verifyNoInteractions(messaging);
    }

    private byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private SimpMessageHeaderAccessor headers() {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId("s1");
        headers.setSessionAttributes(Map.of(WebSocketSessionCredentials.ATTRIBUTE_NAME, credentials,
                CsrfToken.class.getName(), new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "original-csrf")));
        return headers;
    }
}
