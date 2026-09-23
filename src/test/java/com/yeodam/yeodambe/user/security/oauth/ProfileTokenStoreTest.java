package com.yeodam.yeodambe.user.security.oauth;

import com.yeodam.yeodambe.user.entity.ProfileTokenEntity;
import com.yeodam.yeodambe.user.repository.ProfileTokenRepository;
import com.yeodam.yeodambe.user.security.TokenHasher;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({ProfileTokenStore.class, TokenHasher.class})
class ProfileTokenStoreTest {

    @Autowired
    private ProfileTokenStore profileTokenStore;

    @Autowired
    private ProfileTokenRepository profileTokenRepository;

    @Autowired
    private TokenHasher tokenHasher;

    @Test
    void lookupKeepsTokenUntilExplicitDeletion() {
        String token = "profile-token-lookup";
        KakaoUserIdentity identity = new KakaoUserIdentity(
                "123456789",
                "member@example.com",
                "https://k.kakaocdn.net/profile-token-thumbnail.jpg"
        );

        profileTokenStore.save(token, identity);

        assertThat(profileTokenStore.find(token)).contains(identity);
        assertThat(profileTokenStore.find(token)).contains(identity);

        profileTokenStore.delete(token);

        assertThat(profileTokenStore.find(token)).isEmpty();
    }

    @Test
    void storesHashAndTenMinuteExpiration() {
        String token = "profile-token-hash";
        LocalDateTime beforeSave = LocalDateTime.now();

        profileTokenStore.save(
                token,
                new KakaoUserIdentity("123456789", "member@example.com")
        );

        ProfileTokenEntity saved = profileTokenRepository
                .findByTokenHashForUpdate(tokenHasher.hash(token))
                .orElseThrow();

        assertThat(saved.getTokenHash()).isNotEqualTo(token);
        assertThat(saved.getExpiresAt())
                .isBetween(
                        beforeSave.plusMinutes(10),
                        LocalDateTime.now().plusMinutes(10)
                );
    }

    @Test
    void expiredTokenCannotBeFoundAndIsDeleted() {
        String token = "profile-token-expired";
        String tokenHash = tokenHasher.hash(token);

        profileTokenRepository.save(new ProfileTokenEntity(
                tokenHash,
                "123456789",
                "member@example.com",
                LocalDateTime.now().minusSeconds(1)
        ));

        assertThat(profileTokenStore.find(token)).isEmpty();
        assertThat(profileTokenRepository.findByTokenHashForUpdate(tokenHash))
                .isEmpty();
    }
}
