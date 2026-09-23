package com.yeodam.yeodambe.user.security.oauth;

import com.yeodam.yeodambe.user.entity.LoginTicketEntity;
import com.yeodam.yeodambe.user.repository.LoginTicketRepository;
import com.yeodam.yeodambe.user.security.TokenHasher;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({LoginTicketStore.class, TokenHasher.class})
class LoginTicketStoreTest {

    @Autowired
    private LoginTicketStore loginTicketStore;

    @Autowired
    private LoginTicketRepository loginTicketRepository;

    @Autowired
    private TokenHasher tokenHasher;

    @Test
    void ticketCanBeConsumedOnlyOnce() {
        String ticket = "login-ticket-once";

        KakaoUserIdentity identity =
                new KakaoUserIdentity(
                        "123456789",
                        "member@example.com",
                        "https://k.kakaocdn.net/login-ticket-thumbnail.jpg"
                );

        loginTicketStore.save(
                ticket,
                identity,
                "browser-1"
        );

        assertThat(loginTicketStore.consume(ticket, "browser-1"))
                .contains(identity);

        assertThat(loginTicketStore.consume(ticket, "browser-1"))
                .isEmpty();
    }

    @Test
    void storesHashesAndOneMinuteExpiration() {
        String ticket = "login-ticket-hash";
        String browserContext = "browser-hash";
        LocalDateTime beforeSave = LocalDateTime.now();

        loginTicketStore.save(
                ticket,
                new KakaoUserIdentity(
                        "123456789",
                        "member@example.com"
                ),
                browserContext
        );

        LoginTicketEntity saved = loginTicketRepository
                .findByTicketHashForUpdate(tokenHasher.hash(ticket))
                .orElseThrow();

        assertThat(saved.getTicketHash()).isNotEqualTo(ticket);
        assertThat(saved.getBrowserContextHash())
                .isEqualTo(tokenHasher.hash(browserContext));
        assertThat(saved.getExpiresAt())
                .isBetween(
                        beforeSave.plusMinutes(1),
                        LocalDateTime.now().plusMinutes(1)
                );
    }

    @Test
    void differentBrowserCannotConsumeTicket() {
        String ticket = "login-ticket-browser";

        KakaoUserIdentity identity =
                new KakaoUserIdentity(
                        "123456789",
                        "member@example.com"
                );

        loginTicketStore.save(
                ticket,
                identity,
                "browser-1"
        );

        assertThat(
                loginTicketStore.consume(ticket, "browser-2")
        ).isEmpty();

        assertThat(
                loginTicketStore.consume(ticket, "browser-1")
        ).contains(identity);
    }

    @Test
    void expiredTicketCannotBeConsumedAndIsDeleted() {
        String ticket = "login-ticket-expired";
        String ticketHash = tokenHasher.hash(ticket);

        loginTicketRepository.save(new LoginTicketEntity(
                ticketHash,
                tokenHasher.hash("browser-1"),
                "123456789",
                "member@example.com",
                LocalDateTime.now().minusSeconds(1)
        ));

        assertThat(loginTicketStore.consume(ticket, "browser-1"))
                .isEmpty();
        assertThat(loginTicketRepository.findByTicketHashForUpdate(ticketHash))
                .isEmpty();
    }
}
