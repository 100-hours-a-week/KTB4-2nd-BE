package com.yeodam.yeodambe.trip.entity;

import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

@Entity
@Table(name = "trip_share_users", uniqueConstraints = @UniqueConstraint(
        name = "uk_trip_share_trip_grantee", columnNames = {"trip_id", "grantee_user_id"}
), check = @CheckConstraint(name = "ck_trip_share_permission", constraint = "permission IN ('READ', 'WRITE')"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TripShareUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "trip_share_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long id;

    @Column(name = "trip_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long tripId;

    @Column(name = "user_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long userId;

    @Column(name = "grantee_user_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long granteeUserId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", referencedColumnName = "trip_id", nullable = false,
            insertable = false, updatable = false, columnDefinition = "BIGINT UNSIGNED",
            foreignKey = @ForeignKey(name = "fk_trip_share_trip"))
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "user_id", nullable = false,
            insertable = false, updatable = false, columnDefinition = "BIGINT UNSIGNED",
            foreignKey = @ForeignKey(name = "fk_trip_share_issuer"))
    private User issuer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "grantee_user_id", referencedColumnName = "user_id", nullable = false,
            insertable = false, updatable = false, columnDefinition = "BIGINT UNSIGNED",
            foreignKey = @ForeignKey(name = "fk_trip_share_grantee"))
    private User grantee;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'READ'")
    @Column(name = "permission", nullable = false, length = 10, columnDefinition = "VARCHAR(10)")
    private TripSharePermission permission;

    @Column(name = "expires_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime expiresAt;

    @ColumnDefault("CURRENT_TIMESTAMP(6)")
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false,
            columnDefinition = "DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)")
    private LocalDateTime updatedAt;

    @ColumnDefault("NULL")
    @Column(name = "deleted_at", nullable = true, columnDefinition = "DATETIME(6)")
    private LocalDateTime deletedAt;

    public TripShareUser(
            Long tripId,
            Long userId,
            Long granteeUserId,
            TripSharePermission permission,
            LocalDateTime expiresAt,
            LocalDateTime now
    ) {
        this.tripId = tripId;
        this.userId = userId;
        this.granteeUserId = granteeUserId;
        this.permission = permission;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean isEffective(LocalDateTime now) {
        return deletedAt == null && expiresAt.isAfter(now);
    }

    public void reactivate(TripSharePermission permission, LocalDateTime expiresAt, LocalDateTime now) {
        this.permission = permission;
        this.expiresAt = expiresAt;
        this.deletedAt = null;
        this.updatedAt = now;
    }
}
