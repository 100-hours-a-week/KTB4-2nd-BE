package com.yeodam.yeodambe.user.performance;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthCsrfFixtureTest {
    @Test @SuppressWarnings("unchecked")
    void restoresSelectedStoreAndPreservesUnrelatedContexts() {
        try (var pool = new com.zaxxer.hikari.HikariDataSource()) {
            pool.setJdbcUrl("jdbc:h2:mem:csrf_fixture;MODE=MySQL");
            var server = new AuthPerformanceServerTest();
            server.jdbc = new JdbcTemplate(pool);
            server.jdbc.execute("create table csrf_tokens(browser_context_hash varchar(100) primary key, token_value varchar(100), expires_at timestamp)");
            server.jdbc.update("insert into csrf_tokens values ('other','keep',CURRENT_TIMESTAMP)");
            server.redis = mock(StringRedisTemplate.class);
            RedisOperations<String, String> writes = mock(RedisOperations.class);
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(writes.opsForValue()).thenReturn(values);
            when(server.redis.executePipelined(any(SessionCallback.class))).thenAnswer(invocation -> {
                SessionCallback<?> callback = invocation.getArgument(0);
                callback.execute(writes);
                return List.of();
            });
            String key = "yeodam:performance:authperf:csrf-test:csrf:hash";
            when(server.redis.keys("yeodam:performance:authperf:csrf-test:csrf:*")).thenReturn(Set.of(key));
            List<Object[]> contexts = java.util.Collections.singletonList(new Object[]{"hash", "token", LocalDateTime.now().plusDays(7)});
            server.seedCsrf("csrf-test", contexts, "rdb");
            assertEquals("token", server.jdbc.queryForObject("select token_value from csrf_tokens where browser_context_hash='hash'", String.class));
            server.jdbc.update("update csrf_tokens set token_value='changed' where browser_context_hash='hash'");
            server.seedCsrf("csrf-test", contexts, "rdb");
            assertEquals("token", server.jdbc.queryForObject("select token_value from csrf_tokens where browser_context_hash='hash'", String.class));
            server.seedCsrf("csrf-test", contexts, "redis");
            server.seedCsrf("csrf-test", contexts, "redis");
            assertEquals(0L, server.csrfRows(contexts));
            assertEquals("keep", server.jdbc.queryForObject("select token_value from csrf_tokens where browser_context_hash='other'", String.class));
            verify(values, times(2)).set(eq(key), eq("token"), eq(java.time.Duration.ofDays(7)));
        }
    }
}
