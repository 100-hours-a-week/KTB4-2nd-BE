package com.yeodam.yeodambe.user.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "profile_tokens",
        indexes = @Index(
                name = "idx_profile_tokens_expires_at",
                columnList = "expires_at"
        )
)
public class ProfileTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "profile_token_id")
    private Long profileTokenId;

    @Column(
            name = "token_hash",
            nullable = false,
            unique = true,
            length = 64
    )
    private String tokenHash;

    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "profile_image_url", length = 2048)
    private String profileImageUrl;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private LocalDateTime createdAt;

    public ProfileTokenEntity(
            String tokenHash,
            String providerUserId,
            String email,
            String profileImageUrl,
            LocalDateTime expiresAt
    ) {
        this.tokenHash = tokenHash;
        this.providerUserId = providerUserId;
        this.email = email;
        this.profileImageUrl = profileImageUrl;
        this.expiresAt = expiresAt;
    }

    public ProfileTokenEntity(
            String tokenHash,
            String providerUserId,
            String email,
            LocalDateTime expiresAt
    ) {
        this(
                tokenHash,
                providerUserId,
                email,
                null,
                expiresAt
        );
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }
}