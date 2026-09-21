package com.yeodam.yeodambe.user.security.oauth;

import com.yeodam.yeodambe.user.entity.OAuthStateEntity;
import com.yeodam.yeodambe.user.repository.OAuthStateRepository;
import com.yeodam.yeodambe.user.security.TokenHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({OAuthStateStore.class, TokenHasher.class})
class OAuthStateStoreTest {

    @Autowired
    private OAuthStateStore oauthStateStore;

    @Autowired
    private OAuthStateRepository oauthStateRepository;

    @Autowired
    private TokenHasher tokenHasher;

    @Test
    void stateCanBeConsumedOnlyOnce() {
        String state = "oauth-state-1";
        String browserContext = "browser-1";

        oauthStateStore.save(state, browserContext);

        assertThat(oauthStateStore.consume(state, browserContext))
                .isTrue();
        assertThat(oauthStateStore.consume(state, browserContext))
                .isFalse();
    }

    @Test
    void differentBrowserCannotConsumeState() {
        String state = "oauth-state-2";

        oauthStateStore.save(state, "browser-1");

        assertThat(oauthStateStore.consume(state, "browser-2"))
                .isFalse();
        assertThat(oauthStateStore.consume(state, "browser-1"))
                .isTrue();
    }

    @Test
    void storesHashesAndFiveMinuteExpiration() {
        String state = "oauth-state-hash";
        String browserContext = "browser-hash";
        LocalDateTime beforeSave = LocalDateTime.now();

        oauthStateStore.save(state, browserContext);

        OAuthStateEntity saved = oauthStateRepository
                .findByStateHashForUpdate(tokenHasher.hash(state))
                .orElseThrow();

        assertThat(saved.getStateHash()).isNotEqualTo(state);
        assertThat(saved.getBrowserContextHash())
                .isEqualTo(tokenHasher.hash(browserContext));
        assertThat(saved.getExpiresAt())
                .isBetween(
                        beforeSave.plusMinutes(5),
                        LocalDateTime.now().plusMinutes(5)
                );
    }

    @Test
    void expiredStateCannotBeConsumedAndIsDeleted() {
        String state = "oauth-state-expired";
        String stateHash = tokenHasher.hash(state);

        oauthStateRepository.save(new OAuthStateEntity(
                stateHash,
                tokenHasher.hash("browser-1"),
                LocalDateTime.now().minusSeconds(1)
        ));

        assertThat(oauthStateStore.consume(state, "browser-1"))
                .isFalse();
        assertThat(oauthStateRepository.findByStateHashForUpdate(stateHash))
                .isEmpty();
    }
}
