package com.yeodam.yeodambe.user.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = {
        JwtConfig.class,
        AccessTokenIssuer.class
})
@ActiveProfiles("test")
class JwtSigningIntegrationTest {

    @Autowired
    private AccessTokenIssuer accessTokenIssuer;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private Clock clock;

    @Test
    void issuedTokenCanBeVerifiedAndDecoded() {
        String token = accessTokenIssuer.issue(42L, "sid-1");

        Jwt decoded = jwtDecoder.decode(token);

        assertThat(decoded.getSubject()).isEqualTo("42");
        assertThat(decoded.getClaimAsString("sid")).isEqualTo("sid-1");
        assertThat(decoded.getIssuer().toString())
                .isEqualTo("https://api.yeodam.test");
        assertThat(decoded.getAudience()).containsExactly("yeodam-api");
    }

    @Test
    void rejectsTokenIssuedForDifferentAudience() {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("https://api.yeodam.test")
                .audience(List.of("different-api"))
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(30)))
                .subject("42")
                .claim("sid", "sid-1")
                .build();
        JwsHeader header = JwsHeader
                .with(MacAlgorithm.HS256)
                .build();
        String token = jwtEncoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }
}
