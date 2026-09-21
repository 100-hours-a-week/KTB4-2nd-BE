package com.yeodam.yeodambe.user.security.session;

import com.yeodam.yeodambe.user.entity.LoginSessionEntity;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.LoginSessionRepository;
import com.yeodam.yeodambe.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class LoginSessionStore {

    private static final Duration SESSION_TTL =
            Duration.ofDays(7);

    private final LoginSessionRepository loginSessionRepository;
    private final UserRepository userRepository;

    @Transactional
    public void save(
            String sid,
            Long userId,
            String refreshTokenHash
    ) {
        User user = userRepository.getReferenceById(userId);

        LoginSessionEntity entity = new LoginSessionEntity(
                sid,
                user,
                refreshTokenHash,
                LocalDateTime.now().plus(SESSION_TTL)
        );

        loginSessionRepository.save(entity);
    }

    @Transactional
    public Optional<LoginSession> findBySid(String sid) {
        if (sid == null || sid.isBlank()) {
            return Optional.empty();
        }

        Optional<LoginSessionEntity> result =
                loginSessionRepository.findBySid(sid);

        if (result.isEmpty()) {
            return Optional.empty();
        }

        LoginSessionEntity entity = result.get();

        if (entity.isExpired(LocalDateTime.now())) {
            loginSessionRepository.delete(entity);
            return Optional.empty();
        }

        return Optional.of(toLoginSession(entity));
    }

    @Transactional
    public Optional<String> findSidByRefreshTokenHash(
            String refreshTokenHash
    ) {
        if (refreshTokenHash == null
                || refreshTokenHash.isBlank()) {
            return Optional.empty();
        }

        Optional<LoginSessionEntity> result =
                loginSessionRepository.findByRefreshTokenHash(
                        refreshTokenHash
                );

        if (result.isEmpty()) {
            return Optional.empty();
        }

        LoginSessionEntity entity = result.get();

        if (entity.isExpired(LocalDateTime.now())) {
            loginSessionRepository.delete(entity);
            return Optional.empty();
        }

        return Optional.of(entity.getSid());
    }

    @Transactional
    public Optional<Long> rotate(
            String oldHash,
            String newHash
    ) {
        if (oldHash == null
                || oldHash.isBlank()
                || newHash == null
                || newHash.isBlank()) {
            return Optional.empty();
        }

        Optional<LoginSessionEntity> result =
                loginSessionRepository
                        .findByRefreshTokenHashForUpdate(oldHash);

        if (result.isEmpty()) {
            return Optional.empty();
        }

        LoginSessionEntity entity = result.get();

        if (entity.isExpired(LocalDateTime.now())) {
            loginSessionRepository.delete(entity);
            return Optional.empty();
        }

        Long userId = entity.getUser().getUserId();

        entity.rotateRefreshToken(
                newHash,
                LocalDateTime.now().plus(SESSION_TTL)
        );

        return Optional.of(userId);
    }

    private LoginSession toLoginSession(
            LoginSessionEntity entity
    ) {
        return new LoginSession(
                entity.getUser().getUserId(),
                entity.getRefreshTokenHash()
        );
    }
}