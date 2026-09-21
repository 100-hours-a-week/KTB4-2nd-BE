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
        name = "login_tickets",
        indexes = @Index(
                name = "idx_login_tickets_expires_at",
                columnList = "expires_at"
        )
)
public class LoginTicketEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "login_ticket_id")
    private Long loginTicketId;

    @Column(
            name = "ticket_hash",
            nullable = false,
            unique = true,
            length = 64
    )
    private String ticketHash;

    @Column(
            name = "browser_context_hash",
            nullable = false,
            length = 64
    )
    private String browserContextHash;

    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

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

    public LoginTicketEntity(
            String ticketHash,
            String browserContextHash,
            String providerUserId,
            String email,
            LocalDateTime expiresAt
    ) {
        this.ticketHash = ticketHash;
        this.browserContextHash = browserContextHash;
        this.providerUserId = providerUserId;
        this.email = email;
        this.expiresAt = expiresAt;
    }

    public boolean matchesBrowserContext(String browserContextHash) {
        return this.browserContextHash.equals(browserContextHash);
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }
}