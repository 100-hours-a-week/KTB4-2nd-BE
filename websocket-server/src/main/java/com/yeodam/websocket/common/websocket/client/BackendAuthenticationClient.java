package com.yeodam.websocket.common.websocket.client;

import com.yeodam.websocket.common.websocket.config.BackendAuthenticationProperties;
import com.yeodam.websocket.common.websocket.client.response.BackendAuthenticationResponse;
import com.yeodam.websocket.common.websocket.exception.BackendAuthenticationException;
import com.yeodam.websocket.common.websocket.exception.BackendAuthenticationFailure;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.time.Instant;

@Component
public class BackendAuthenticationClient {

    private final RestClient restClient;
    private final BackendAuthenticationProperties properties;

    public BackendAuthenticationClient(
            @Qualifier("backendAuthenticationRestClient")
            RestClient restClient,
            BackendAuthenticationProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public BackendAuthenticationResponse.AuthenticationData authenticate(
            String accessToken,
            String browserContext
    ) {
        if (isBlank(properties.serviceToken())) {
            throw new BackendAuthenticationException(
                    BackendAuthenticationFailure.SERVICE_TOKEN_MISSING
            );
        }
        if (isBlank(accessToken)) {
            throw new BackendAuthenticationException(
                    BackendAuthenticationFailure.USER_UNAUTHORIZED
            );
        }
        if (isBlank(browserContext)) {
            throw new BackendAuthenticationException(
                    BackendAuthenticationFailure.CSRF_INVALID
            );
        }

        try {
            return restClient.get()
                    .uri("/api/internal/websocket/authentication")
                    .header(
                            "X-Internal-Service-Token",
                            properties.serviceToken()
                    )
                    .cookie("accessToken", accessToken)
                    .cookie("CSRF_CONTEXT", browserContext)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();

                        if (status == 401) {
                            throw new BackendAuthenticationException(
                                    BackendAuthenticationFailure.USER_UNAUTHORIZED
                            );
                        }
                        if (status >= 500) {
                            throw new BackendAuthenticationException(
                                    BackendAuthenticationFailure.BACKEND_UNAVAILABLE
                            );
                        }
                        if (status != 200 && status != 403) {
                            throw new BackendAuthenticationException(
                                    BackendAuthenticationFailure.INVALID_RESPONSE
                            );
                        }

                        BackendAuthenticationResponse body =
                                response.bodyTo(BackendAuthenticationResponse.class);

                        if (status == 403) {
                            if (body != null
                                    && "CSRF_TOKEN_INVALID".equals(body.message())) {
                                throw new BackendAuthenticationException(
                                        BackendAuthenticationFailure.CSRF_INVALID
                                );
                            }
                            if (body != null
                                    && "FORBIDDEN".equals(body.message())) {
                                throw new BackendAuthenticationException(
                                        BackendAuthenticationFailure.SERVICE_FORBIDDEN
                                );
                            }
                            throw new BackendAuthenticationException(
                                    BackendAuthenticationFailure.INVALID_RESPONSE
                            );
                        }

                        return validateResponse(body);
                    });
        } catch (ResourceAccessException exception) {
            throw new BackendAuthenticationException(
                    BackendAuthenticationFailure.BACKEND_UNAVAILABLE
            );
        } catch (RestClientException exception) {
            throw new BackendAuthenticationException(
                    BackendAuthenticationFailure.INVALID_RESPONSE
            );
        }
    }

    private BackendAuthenticationResponse.AuthenticationData validateResponse(
            BackendAuthenticationResponse response
    ) {
        if (response == null
                || !"WEBSOCKET_AUTHENTICATION_SUCCESS".equals(response.message())
                || response.data() == null) {
            throw new BackendAuthenticationException(
                    BackendAuthenticationFailure.INVALID_RESPONSE
            );
        }

        BackendAuthenticationResponse.AuthenticationData data = response.data();

        if (data.userId() == null || data.userId() <= 0
                || isBlank(data.sessionId())
                || data.accessTokenExpiresAt() == null
                || isBlank(data.csrfToken())) {
            throw new BackendAuthenticationException(
                    BackendAuthenticationFailure.INVALID_RESPONSE
            );
        }

        if (!data.accessTokenExpiresAt().isAfter(Instant.now())) {
            throw new BackendAuthenticationException(
                    BackendAuthenticationFailure.USER_UNAUTHORIZED
            );
        }

        return data;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}