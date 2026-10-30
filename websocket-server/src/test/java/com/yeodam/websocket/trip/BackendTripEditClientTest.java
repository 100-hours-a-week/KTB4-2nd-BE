package com.yeodam.websocket.trip;

import com.yeodam.websocket.common.websocket.config.BackendAuthenticationProperties;
import com.yeodam.websocket.common.websocket.security.WebSocketSessionCredentials;
import com.yeodam.websocket.trip.client.BackendTripEditClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class BackendTripEditClientTest {

    private final WebSocketSessionCredentials credentials = new WebSocketSessionCredentials("access", "context");
    private final ObjectMapper mapper = new ObjectMapper();
    private BackendTripEditClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://backend");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BackendTripEditClient(builder.build(), new BackendAuthenticationProperties(
                URI.create("http://backend"), "service", Duration.ofSeconds(1), Duration.ofSeconds(2)
        ));
    }

    @Test
    void checksIdentityPermissionAndValidityUsingTheSessionCookies() {
        server.expect(requestTo("http://backend/api/internal/websocket/trips/14/access"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Service-Token", "service"))
                .andExpect(header("Cookie", org.hamcrest.Matchers.containsString("accessToken=access")))
                .andExpect(header("Cookie", org.hamcrest.Matchers.containsString("CSRF_CONTEXT=context")))
                .andRespond(withSuccess(access(14, 42, "READ", Instant.now().plusSeconds(20)), MediaType.APPLICATION_JSON));
        assertThatCode(() -> client.checkAccess(14, 42, credentials)).doesNotThrowAnyException();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "trip", "permission", "expired", "window", "malformed"})
    void rejectsUntrustedAccessResponses(String invalid) {
        String response = access(invalid.equals("trip") ? 15 : 14, invalid.equals("user") ? 99 : 42,
                invalid.equals("permission") ? "NONE" : "READ",
                Instant.now().plusSeconds(invalid.equals("expired") ? -1 : invalid.equals("window") ? 60 : 20));
        if (invalid.equals("malformed")) {
            response = "{}";
        }
        server.expect(requestTo("http://backend/api/internal/websocket/trips/14/access"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.checkAccess(14, 42, credentials)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void forwardsCommandAndOriginalCsrfWithoutClientIdentity() {
        server.expect(requestTo("http://backend/api/internal/websocket/trips/14/update"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-CSRF-TOKEN", "original-csrf"))
                .andExpect(header("X-Internal-Service-Token", "service"))
                .andExpect(content().json("{\"requestId\":\"r1\",\"changes\":{\"tripName\":\"Jeju\"}}"))
                .andRespond(withSuccess("{\"message\":\"TRIP_UPDATE_SUCCESS\",\"data\":{\"requestId\":\"r1\",\"tripId\":14,\"status\":\"SUCCEEDED\",\"sequence\":3}}", MediaType.APPLICATION_JSON));
        client.execute(14, "update", mapper.readTree("{\"requestId\":\"r1\",\"changes\":{\"tripName\":\"Jeju\"}}"), credentials, "original-csrf");
        server.verify();
    }

    @Test
    void acceptsDurableRejectionWithoutSequence() {
        server.expect(requestTo("http://backend/api/internal/websocket/trips/14/update"))
                .andRespond(withSuccess("{\"message\":\"INVALID_TRIP_REQUEST\",\"data\":{\"requestId\":\"r1\",\"tripId\":14,\"status\":\"REJECTED\"}}", MediaType.APPLICATION_JSON));
        assertThatCode(() -> client.execute(14, "update", mapper.readTree("{\"requestId\":\"r1\"}"), credentials, "csrf"))
                .doesNotThrowAnyException();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "wrongTrip", "wrongRequest", "unknownStatus", "sequence", "server", "timeout"})
    void keepsAmbiguousOutcomesAsExceptions(String invalid) {
        String response = "{\"data\":{\"requestId\":\"%s\",\"tripId\":%d,\"status\":\"%s\",\"sequence\":%d}}"
                .formatted(invalid.equals("wrongRequest") ? "other" : "r1", invalid.equals("wrongTrip") ? 99 : 14,
                        invalid.equals("unknownStatus") ? "UNKNOWN" : "SUCCEEDED", invalid.equals("sequence") ? -1 : 3);
        if (invalid.equals("empty")) {
            response = "{}";
        }
        org.springframework.test.web.client.ResponseActions expectation = server.expect(requestTo("http://backend/api/internal/websocket/trips/14/update"));
        if (invalid.equals("server")) {
            expectation.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        } else if (invalid.equals("timeout")) {
            expectation.andRespond(withException(new java.net.SocketTimeoutException()));
        } else {
            expectation.andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        }
        assertThatThrownBy(() -> client.execute(14, "update", mapper.readTree("{\"requestId\":\"r1\"}"), credentials, "csrf"))
                .isInstanceOf(RestClientException.class);
    }

    private String access(long tripId, long userId, String permission, Instant until) {
        return "{\"message\":\"TRIP_WEBSOCKET_ACCESS_FOUND\",\"data\":{\"tripId\":%d,\"userId\":%d,\"permission\":\"%s\",\"checkedAt\":\"%s\",\"accessValidUntil\":\"%s\"}}"
                .formatted(tripId, userId, permission, Instant.now(), until);
    }
}
