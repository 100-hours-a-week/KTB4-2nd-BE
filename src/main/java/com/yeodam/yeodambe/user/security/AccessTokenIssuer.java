package com.yeodam.yeodambe.user.security;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Component
public class AccessTokenIssuer {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock clock;

    public AccessTokenIssuer(
            JwtEncoder jwtEncoder,
            JwtProperties properties,
            Clock clock
    ) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    public String issue(Long userId, String sid) {
        Instant issuedAt = clock.instant();
        Instant expiresAt =
                issuedAt.plus(properties.accessTokenTtl());

        JwsHeader header = JwsHeader
                .with(MacAlgorithm.HS256)
                .build();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(userId.toString())
                .claim("sid", sid)
                .build();

        return jwtEncoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();
    }
}