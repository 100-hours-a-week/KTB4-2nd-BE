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
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(name="email", nullable=false, length = 255)
    private String email;

    @Column(name = "nickname", nullable = false, length = 10)
    private String nickname;

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

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public User(String email, String nickname) {
        this.email = email;
        this.nickname = nickname;
    }

    public void withdraw(LocalDateTime withdrawnAt) {
        this.deletedAt = withdrawnAt;
    }

}
