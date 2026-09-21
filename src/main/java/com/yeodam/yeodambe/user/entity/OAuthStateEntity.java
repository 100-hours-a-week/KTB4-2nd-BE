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
        name = "oauth_states",
        indexes = @Index(
                name = "idx_oauth_states_expires_at",
                columnList = "expires_at"
        )
)
public class OAuthStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "oauth_state_id")
    private Long oauthStateId;

    @Column(
            name = "state_hash",
            nullable = false,
            unique = true,
            length = 64
    )
    private String stateHash;

    @Column(
            name = "browser_context_hash",
            nullable = false,
            length = 64
    )
    private String browserContextHash;

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

    public OAuthStateEntity(
            String stateHash,
            String browserContextHash,
            LocalDateTime expiresAt
    ) {
        this.stateHash = stateHash;
        this.browserContextHash = browserContextHash;
        this.expiresAt = expiresAt;
    }

    public boolean matchesBrowserContext(String browserContextHash) {
        return this.browserContextHash.equals(browserContextHash);
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }
}