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
        name = "login_sessions",
        indexes = @Index(
                name = "idx_login_sessions_expires_at",
                columnList = "expires_at"
        )
)
public class LoginSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "login_session_id")
    private Long loginSessionId;

    @Column(
            name = "sid",
            nullable = false,
            unique = true,
            length = 36
    )
    private String sid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(
            name = "refresh_token_hash",
            nullable = false,
            unique = true,
            length = 64
    )
    private String refreshTokenHash;

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

    public LoginSessionEntity(
            String sid,
            User user,
            String refreshTokenHash,
            LocalDateTime expiresAt
    ) {
        this.sid = sid;
        this.user = user;
        this.refreshTokenHash = refreshTokenHash;
        this.expiresAt = expiresAt;
    }

    public void rotateRefreshToken(
            String newRefreshTokenHash,
            LocalDateTime newExpiresAt
    ) {
        this.refreshTokenHash = newRefreshTokenHash;
        this.expiresAt = newExpiresAt;
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }
}