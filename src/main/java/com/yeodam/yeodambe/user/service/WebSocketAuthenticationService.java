package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.common.exception.CsrfTokenInvalidException;
import com.yeodam.yeodambe.user.exception.UserInternalErrorMessage;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.service.response.WebSocketAuthenticationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WebSocketAuthenticationService {

    private final CsrfTokenStore csrfTokenStore;

    public WebSocketAuthenticationResponse authenticate(
            Jwt jwt,
            String browserContext
    ) {
        if (jwt.getExpiresAt() == null) {
            throw new AuthenticationCredentialsNotFoundException(
                    UserInternalErrorMessage.ACCESS_TOKEN_EXPIRATION_MISSING.message()
            );
        }

        String csrfToken = csrfTokenStore.find(browserContext);
        if (csrfToken == null) {
            throw new CsrfTokenInvalidException();
        }

        return new WebSocketAuthenticationResponse(
                Long.valueOf(jwt.getSubject()),
                jwt.getClaimAsString("sid"),
                jwt.getExpiresAt(),
                csrfToken
        );
    }
}
