package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.exception.RefreshTokenInvalidOrExpiredException;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.session.LoginSession;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import com.yeodam.yeodambe.user.security.session.RefreshTokenGenerator;
import com.yeodam.yeodambe.user.security.session.RefreshTokenHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccessTokenRefreshService {

    private final RefreshTokenHasher refreshTokenHasher;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final LoginSessionStore loginSessionStore;
    private final UserRepository userRepository;
    private final AccessTokenIssuer accessTokenIssuer;

    public Result refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new RefreshTokenInvalidOrExpiredException();
        }

        String oldHash = refreshTokenHasher.hash(refreshToken);
        String sid = loginSessionStore.findSidByRefreshTokenHash(oldHash)
                .orElseThrow(RefreshTokenInvalidOrExpiredException::new);

        LoginSession session = loginSessionStore.findBySid(sid)
                .orElseThrow(RefreshTokenInvalidOrExpiredException::new);

        if (!oldHash.equals(session.refreshTokenHash())) {
            throw new RefreshTokenInvalidOrExpiredException();
        }

        User user = userRepository.findById(session.userId())
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(RefreshTokenInvalidOrExpiredException::new);

        String newRefreshToken = refreshTokenGenerator.generate();
        String newHash = refreshTokenHasher.hash(newRefreshToken);
        String newAccessToken = accessTokenIssuer.issue(user.getUserId(), sid);

        loginSessionStore.rotate(oldHash, newHash)
                .orElseThrow(RefreshTokenInvalidOrExpiredException::new);

        return new Result(newAccessToken, newRefreshToken);
    }

    public record Result(String accessToken, String refreshToken) {
    }
}