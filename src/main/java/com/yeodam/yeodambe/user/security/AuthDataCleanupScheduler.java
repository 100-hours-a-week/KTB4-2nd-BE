package com.yeodam.yeodambe.user.security;

import com.yeodam.yeodambe.user.repository.CsrfTokenRepository;
import com.yeodam.yeodambe.user.repository.LoginSessionRepository;
import com.yeodam.yeodambe.user.repository.LoginTicketRepository;
import com.yeodam.yeodambe.user.repository.OAuthStateRepository;
import com.yeodam.yeodambe.user.repository.ProfileTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class AuthDataCleanupScheduler {

    private final OAuthStateRepository oauthStateRepository;
    private final LoginTicketRepository loginTicketRepository;
    private final ProfileTokenRepository profileTokenRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final LoginSessionRepository loginSessionRepository;

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void deleteExpiredAuthenticationData() {
        LocalDateTime now = LocalDateTime.now();

        oauthStateRepository.deleteByExpiresAtLessThanEqual(now);
        loginTicketRepository.deleteByExpiresAtLessThanEqual(now);
        profileTokenRepository.deleteByExpiresAtLessThanEqual(now);
        csrfTokenRepository.deleteByExpiresAtLessThanEqual(now);
        loginSessionRepository.deleteByExpiresAtLessThanEqual(now);
    }
}