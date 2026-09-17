package com.yeodam.yeodambe.user.security.oauth;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.time.Duration;

@Component
@RequiredArgsConstructor
public class OAuthStateStore {

    private static final String KEY_PREFIX = "oauth:state:";
    private static final Duration STATE_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;

    private static final DefaultRedisScript<Long> CONSUME_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """,
                    Long.class
            );

    public void save(String state, String browserContext) {
        redisTemplate.opsForValue().set(
                key(state),
                browserContext,
                STATE_TTL
        );
    }

    public boolean consume(String state, String browserContext) {
        Long deletedCount = redisTemplate.execute(
                CONSUME_SCRIPT,
                List.of(key(state)),
                browserContext
        );

        return Long.valueOf(1L).equals(deletedCount);
    }

    private String key(String state) {
        return KEY_PREFIX + state;
    }
}