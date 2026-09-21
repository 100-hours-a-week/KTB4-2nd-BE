package com.yeodam.yeodambe.user.security.oauth;

import com.yeodam.yeodambe.user.entity.LoginTicketEntity;
import com.yeodam.yeodambe.user.repository.LoginTicketRepository;
import com.yeodam.yeodambe.user.security.TokenHasher;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class LoginTicketStore {

    private static final Duration TICKET_TTL =
            Duration.ofMinutes(1);

    private final LoginTicketRepository loginTicketRepository;
    private final TokenHasher tokenHasher;

    public void save(
            String ticket,
            KakaoUserIdentity identity,
            String browserContext
    ) {
        LoginTicketEntity entity = new LoginTicketEntity(
                tokenHasher.hash(ticket),
                tokenHasher.hash(browserContext),
                identity.providerUserId(),
                identity.email(),
                LocalDateTime.now().plus(TICKET_TTL)
        );

        loginTicketRepository.save(entity);
    }

    @Transactional
    public Optional<KakaoUserIdentity> consume(
            String ticket,
            String browserContext
    ) {
        if (ticket == null
                || ticket.isBlank()
                || browserContext == null
                || browserContext.isBlank()) {
            return Optional.empty();
        }

        String ticketHash = tokenHasher.hash(ticket);
        String browserContextHash =
                tokenHasher.hash(browserContext);

        Optional<LoginTicketEntity> result =
                loginTicketRepository.findByTicketHashForUpdate(
                        ticketHash
                );

        if (result.isEmpty()) {
            return Optional.empty();
        }

        LoginTicketEntity entity = result.get();

        if (entity.isExpired(LocalDateTime.now())) {
            loginTicketRepository.delete(entity);
            return Optional.empty();
        }

        if (!entity.matchesBrowserContext(browserContextHash)) {
            return Optional.empty();
        }

        KakaoUserIdentity identity = new KakaoUserIdentity(
                entity.getProviderUserId(),
                entity.getEmail()
        );

        loginTicketRepository.delete(entity);
        return Optional.of(identity);
    }
}