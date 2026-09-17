package com.yeodam.yeodambe.user.security.jwt;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

class AccessTokenIssuerTest {

    @Test
    void issuesAccessTokenWithRequiredClaimsAndThirtyMinuteExpiry() {
        JwtEncoder jwtEncoder = mock(JwtEncoder.class);
        Instant now = Instant.parse("2026-09-17T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        JwtProperties properties = new JwtProperties(
                "test-jwt-secret-key-must-be-at-least-32-bytes",
                "https://api.yeodam.test",
                "yeodam-api",
                Duration.ofMinutes(30)
        );
        AccessTokenIssuer issuer = new AccessTokenIssuer(
                jwtEncoder,
                properties,
                clock
        );
        Jwt encodedJwt = Jwt.withTokenValue("encoded-access-token")
                .header("alg", "HS256")
                .claim("sub", "42")
                .build();
        given(jwtEncoder.encode(any())).willReturn(encodedJwt);

        String token = issuer.issue(42L, "sid-1");

        assertThat(token).isEqualTo("encoded-access-token");

        ArgumentCaptor<JwtEncoderParameters> captor =
                ArgumentCaptor.forClass(JwtEncoderParameters.class);
        then(jwtEncoder).should().encode(captor.capture());

        JwtEncoderParameters parameters = captor.getValue();
        JwtClaimsSet claims = parameters.getClaims();

        assertThat(parameters.getJwsHeader().getAlgorithm())
                .isEqualTo(MacAlgorithm.HS256);
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat((String) claims.getClaim("sid")).isEqualTo("sid-1");
        assertThat(claims.getIssuer().toString())
                .isEqualTo("https://api.yeodam.test");
        assertThat(claims.getAudience()).isEqualTo(List.of("yeodam-api"));
        assertThat(claims.getIssuedAt()).isEqualTo(now);
        assertThat(claims.getExpiresAt()).isEqualTo(now.plus(Duration.ofMinutes(30)));
    }
}
