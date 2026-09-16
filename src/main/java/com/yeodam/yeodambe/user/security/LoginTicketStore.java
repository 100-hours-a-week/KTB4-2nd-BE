package com.yeodam.yeodambe.user.security;

import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class LoginTicketStore {

    private static final String KEY_PREFIX = "auth:login-ticket:";
    private static final Duration TICKET_TTL = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void save(
            String ticket,
            KakaoUserIdentity identity,
            String browserContext
    ) {
        try {
            String json = objectMapper.writeValueAsString(identity);

            redisTemplate.opsForValue().set(
                    key(ticket, browserContext),
                    json,
                    TICKET_TTL
            );
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "로그인 티켓 데이터 변환에 실패했습니다.",
                    e
            );
        }
    }

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

        String json = redisTemplate
                .opsForValue()
                .getAndDelete(key(ticket, browserContext));

        if (json == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(
                    objectMapper.readValue(
                            json,
                            KakaoUserIdentity.class
                    )
            );
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "로그인 티켓 데이터 복원에 실패했습니다.",
                    e
            );
        }
    }

    private String key(
            String ticket,
            String browserContext
    ) {
        return KEY_PREFIX + browserContext + ":" + ticket;
    }
}