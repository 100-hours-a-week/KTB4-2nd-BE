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
        name = "csrf_tokens",
        indexes = @Index(
                name = "idx_csrf_tokens_expires_at",
                columnList = "expires_at"
        )
)
public class CsrfTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "csrf_token_id")
    private Long csrfTokenId;

    @Column(
            name = "browser_context_hash",
            nullable = false,
            unique = true,
            length = 64
    )
    private String browserContextHash;

    @Column(name = "token_value", nullable = false, length = 64)
    private String tokenValue;

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

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(
            name = "updated_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private LocalDateTime updatedAt;

    public CsrfTokenEntity(
            String browserContextHash,
            String tokenValue,
            LocalDateTime expiresAt
    ) {
        this.browserContextHash = browserContextHash;
        this.tokenValue = tokenValue;
        this.expiresAt = expiresAt;
    }

    public void update(
            String tokenValue,
            LocalDateTime expiresAt
    ) {
        this.tokenValue = tokenValue;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }
}