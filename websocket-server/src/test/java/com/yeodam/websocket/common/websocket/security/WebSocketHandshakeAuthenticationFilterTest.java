package com.yeodam.websocket.common.websocket.security;

import com.yeodam.websocket.common.websocket.client.BackendAuthenticationClient;
import com.yeodam.websocket.common.websocket.client.response.BackendAuthenticationResponse.AuthenticationData;
import com.yeodam.websocket.common.websocket.exception.BackendAuthenticationException;
import com.yeodam.websocket.common.websocket.exception.BackendAuthenticationFailure;
import jakarta.servlet.http.Cookie;
import com.yeodam.websocket.common.websocket.service.RedisSubscriptionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.messaging.web.socket.server.CsrfTokenHandshakeInterceptor;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class WebSocketHandshakeAuthenticationFilterTest {

    private final BackendAuthenticationClient client = mock(BackendAuthenticationClient.class);
    private final RedisSubscriptionState subscriptionState = new RedisSubscriptionState();
    private final WebSocketHandshakeAuthenticationFilter filter =
            new WebSocketHandshakeAuthenticationFilter(client, subscriptionState);

    @BeforeEach
    void prepareSubscription() {
        subscriptionState.markSubscribed();
    }

    @Test
    void rejectsHandshakeBeforeSubscriptionWithoutCallingBackend() throws Exception {
        RedisSubscriptionState initialState = new RedisSubscriptionState();
        var unavailableFilter = new WebSocketHandshakeAuthenticationFilter(client, initialState);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        unavailableFilter.doFilter(authenticatedRequest(), response,
                (request, nextResponse) -> continued.set(true));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(continued).isFalse();
        verifyNoInteractions(client);
    }

    @Test
    void rejectsHandshakeAfterSubscriptionBecomesUnavailable() throws Exception {
        subscriptionState.markUnavailable();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(authenticatedRequest(), response,
                (request, nextResponse) -> continued.set(true));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(continued).isFalse();
        verifyNoInteractions(client);
    }

    @Test
    void resumesAuthenticationAfterSubscriptionIsRestored() throws Exception {
        subscriptionState.markUnavailable();
        subscriptionState.markSubscribed();
        when(client.authenticate("test-access-token", "test-browser-context"))
                .thenReturn(new AuthenticationData(
                        42L, "test-login-session", Instant.parse("2099-01-01T00:00:00Z"),
                        "test-csrf-token"
                ));
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(authenticatedRequest(), new MockHttpServletResponse(),
                (request, response) -> continued.set(true));

        assertThat(continued).isTrue();
        verify(client).authenticate("test-access-token", "test-browser-context");
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void passesVerifiedIdentityToTheNextFilter() throws Exception {
        Instant expiresAt = Instant.parse("2099-01-01T00:00:00Z");
        when(client.authenticate("test-access-token", "test-browser-context"))
                .thenReturn(new AuthenticationData(
                        42L, "test-login-session", expiresAt, "test-csrf-token"
                ));
        MockHttpServletRequest request = authenticatedRequest();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request, new MockHttpServletResponse(), (nextRequest, response) -> {
            continued.set(true);
            var credentials = (WebSocketSessionCredentials) nextRequest.getAttribute(
                    WebSocketSessionCredentials.ATTRIBUTE_NAME
            );
            assertThat(credentials).isNotNull();
            assertThat(credentials.accessToken()).isEqualTo("test-access-token");
            assertThat(credentials.browserContext()).isEqualTo("test-browser-context");
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication).isNotNull();
            assertThat(authentication.isAuthenticated()).isTrue();
            assertThat(authentication.getName()).isEqualTo("42");
            assertThat(authentication.getCredentials()).isNull();
            assertThat(authentication.getPrincipal()).isEqualTo(
                    new WebSocketPrincipal(42L, "test-login-session", expiresAt)
            );
        });

        assertThat(continued).isTrue();
        verify(client).authenticate("test-access-token", "test-browser-context");
    }

    @Test
    void reusesTheBackendCsrfTokenInSpringHandshakeAttributes() throws Exception {
        when(client.authenticate("test-access-token", "test-browser-context"))
                .thenReturn(new AuthenticationData(
                        42L, "test-login-session", Instant.parse("2099-01-01T00:00:00Z"),
                        "existing-backend-csrf-token"
                ));
        MockHttpServletRequest request = authenticatedRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (nextRequest, nextResponse) -> {
            DeferredCsrfToken deferred = (DeferredCsrfToken) request.getAttribute(
                    DeferredCsrfToken.class.getName()
            );
            assertThat(deferred).isNotNull();
            assertThat(deferred.isGenerated()).isFalse();
            assertThat(deferred.get()).isSameAs(deferred.get());

            var attributes = new HashMap<String, Object>();
            boolean allowed = new CsrfTokenHandshakeInterceptor().beforeHandshake(
                    new ServletServerHttpRequest(request),
                    new ServletServerHttpResponse(response),
                    new TextWebSocketHandler(),
                    attributes
            );

            assertThat(allowed).isTrue();
            CsrfToken copiedToken = (CsrfToken) attributes.get(CsrfToken.class.getName());
            assertThat(copiedToken).isNotNull();
            assertThat(copiedToken.getToken()).isEqualTo("existing-backend-csrf-token");
            assertThat(copiedToken.getHeaderName()).isEqualTo("X-CSRF-TOKEN");
        });

        verify(client).authenticate("test-access-token", "test-browser-context");
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /actuator/health",
            "POST, /ws",
            "GET, /other"
    })
    void skipsAuthenticationForOtherRequests(String method, String path) throws Exception {
        subscriptionState.markUnavailable();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request(method, path), new MockHttpServletResponse(),
                (nextRequest, response) -> continued.set(true));

        assertThat(continued).isTrue();
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @CsvSource({
            "USER_UNAUTHORIZED, 401",
            "CSRF_INVALID, 403",
            "SERVICE_FORBIDDEN, 503",
            "SERVICE_TOKEN_MISSING, 503",
            "BACKEND_UNAVAILABLE, 503",
            "INVALID_RESPONSE, 503"
    })
    void stopsTheChainAndClearsAuthenticationOnFailure(
            BackendAuthenticationFailure failure,
            int expectedStatus
    ) throws Exception {
        when(client.authenticate("test-access-token", "test-browser-context"))
                .thenThrow(new BackendAuthenticationException(failure));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("previous-user", null, List.of())
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(authenticatedRequest(), response,
                (request, nextResponse) -> continued.set(true));

        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        assertThat(continued).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void passesMissingCookiesToTheAuthenticationClient() throws Exception {
        when(client.authenticate(null, null)).thenThrow(
                new BackendAuthenticationException(BackendAuthenticationFailure.USER_UNAUTHORIZED)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();

        filter.doFilter(request("GET", "/ws"), response,
                (request, nextResponse) -> continued.set(true));

        verify(client).authenticate(null, null);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(continued).isFalse();
    }

    private MockHttpServletRequest authenticatedRequest() {
        MockHttpServletRequest request = request("GET", "/ws");
        request.setCookies(
                new Cookie("accessToken", "test-access-token"),
                new Cookie("CSRF_CONTEXT", "test-browser-context")
        );
        return request;
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api" + path);
        request.setContextPath("/api");
        request.setServletPath(path);
        return request;
    }
}
