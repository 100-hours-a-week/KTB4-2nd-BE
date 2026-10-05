package com.yeodam.yeodambe.user.performance;

import com.yeodam.yeodambe.integration.client.AiEc2Starter;
import com.yeodam.yeodambe.user.security.TokenHasher;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.session.LoginSession;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("performance")
@Import(AuthPerformanceServerTest.Config.class)
class AuthPerformanceServerTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static String mysql() {
        String url = System.getProperty("load.mysql", "jdbc:mysql://127.0.0.1:13307/auth_performance?serverTimezone=Asia/Seoul&characterEncoding=utf8");
        if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost|auth-mysql):[0-9]+/auth_performance(\\?.*)?"))
            throw new IllegalArgumentException("Only loopback auth_performance database is allowed");
        if (url.contains("auth-mysql") && !Boolean.getBoolean("load.container"))
            throw new IllegalArgumentException("Container DB requires load.container");
        return url;
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.config.import", () -> "");
        r.add("spring.data.redis.host", () -> Boolean.getBoolean("load.container") ? "auth-redis" : "127.0.0.1");
        r.add("spring.data.redis.port", () -> Boolean.getBoolean("load.container") ? 6379 : 16379);
        r.add("spring.data.redis.username", () -> "");
        r.add("spring.data.redis.password", () -> "");
        r.add("spring.data.redis.ssl.enabled", () -> false);
        r.add("auth.session.key-prefix", () -> "yeodam:performance:authperf:"
                + System.getProperty("load.dataset", "baseline") + ":");
        r.add("spring.datasource.url", AuthPerformanceServerTest::mysql);
        r.add("spring.datasource.username", () -> "yeodam");
        r.add("spring.datasource.password", () -> "yeodam");
        r.add("spring.datasource.hikari.maximum-pool-size", () -> 10);
        r.add("spring.datasource.hikari.minimum-idle", () -> 10);
        r.add("server.address", () -> Boolean.getBoolean("load.container") ? "0.0.0.0" : "127.0.0.1");
        if (Boolean.getBoolean("load.container")) r.add("server.port", () -> 8080);
        r.add("oauth.browser-context-cookie.secure", () -> false);
        r.add("management.endpoints.web.exposure.include", () -> "health,prometheus");
        r.add("auth.jwt.access-token-ttl", () -> "2h");
        r.add("attachment.s3.bucket", () -> "auth-unused");
        r.add("attachment.s3.endpoint", () -> "http://127.0.0.1:1");
        r.add("ai.server.base-url", () -> "http://127.0.0.1:1");
        r.add("ai.server.api-key", () -> "test");
        r.add("ai.server.instance-id", () -> "unused");
        r.add("kakao.local.base-url", () -> "http://127.0.0.1:1");
        r.add("auth.jwt.secret", () -> "auth-performance-local-secret-32-bytes");
        r.add("auth.jwt.issuer", () -> "http://auth-performance.local");
    }
    @TestConfiguration(proxyBeanMethods = false) static class Config {
        @Bean static AuthMeasurements authMeasurements() { return new AuthMeasurements(); }
        @Bean FilterRegistrationBean<org.springframework.web.filter.OncePerRequestFilter> authMeasurementFilter(AuthMeasurements m) {
            var bean = new FilterRegistrationBean<>(m.filter());
            bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
            return bean;
        }
    }
    @MockitoBean AiEc2Starter starter;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessTokenIssuer issuer;
    @Autowired TokenHasher hasher;
    @Autowired LoginSessionStore sessionStore;
    @Autowired com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore csrfStore;
    @Autowired(required = false) StringRedisTemplate redis;
    @Autowired AuthMeasurements measurements;
    @Autowired MeterRegistry registry;
    @LocalServerPort int port;

    @Test void servesRealAuthenticationWithBoundedLifetime() throws Exception {
        String directory = System.getProperty("load.runDir");
        assertNotNull(directory, "Use performance/auth/run.py to launch the bounded server");
        Path root = Path.of(directory);
        String dataset = System.getProperty("load.dataset", "baseline");
        if (!dataset.matches("[a-z0-9-]{1,24}")) throw new IllegalArgumentException("Invalid dataset name");
        int users = Integer.getInteger("load.users", 10000);
        int active = Integer.getInteger("load.activeUsers", 1000);
        if (users < active || active < 1 || users > 100000 || active > 10000) throw new IllegalArgumentException("Invalid fixture counts");
        int budget = Integer.getInteger("load.budgetSeconds", 1200);
        if (budget < 30 || budget > 3600) throw new IllegalArgumentException("Invalid server lifetime");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(budget);
        write(root.resolve("jvm.json"), Map.of("input_arguments", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments(),
                "available_processors", Runtime.getRuntime().availableProcessors(), "max_heap_bytes", Runtime.getRuntime().maxMemory(),
                "gc", java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream().map(java.lang.management.GarbageCollectorMXBean::getName).toList(),
                "java_version", System.getProperty("java.version")));
        seed(root, dataset, users, active);
        measurements.open(root.resolve("server-requests.csv"), registry);
        write(root.resolve("ready.json"), Map.of("port", port, "pid", ProcessHandle.current().pid(),
                "users", users, "sessions", users * 2, "csrf", users, "active_users", active,
                "dataset", dataset, "context_path", "/api"));
        try {
            while (!Files.exists(root.resolve("stop.json")) && System.nanoTime() < deadline) {
                Path reset = root.resolve("reset-request.json");
                if (Files.exists(reset) && measurements.active.get() == 0) {
                    String nonce = Files.readString(reset);
                    seed(root, dataset, users, active);
                    write(root.resolve("reset-ready.json"), Map.of("nonce", nonce, "idle", true));
                    Files.delete(reset);
                }
                measurements.flush();
                write(root.resolve("idle.json"), Map.of("active_requests", measurements.active.get()));
                Thread.sleep(200);
            }
            long drain = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (measurements.active.get() > 0 && System.nanoTime() < drain) Thread.sleep(100);
            assertEquals(0, measurements.active.get(), "Requests remain; dataset preserved");
            write(root.resolve("validation.json"), Map.of("idle", true, "dataset_preserved", true));
        } finally { measurements.close(); }
        // Dataset is intentionally retained for A/B. No broad cleanup or shared-data deletion.
    }
    void seed(Path root, String dataset, int users, int active) throws Exception {
        String prefix = "authperf-" + dataset + "-";
        List<Object[]> members = new ArrayList<>();
        for (int i = 0; i < users; i++) members.add(new Object[]{prefix + i + "@fixture.invalid", "인증측정"});
        jdbc.batchUpdate("insert ignore into users(email,nickname) values (?,?)", members);
        Map<String, Long> ids = new java.util.HashMap<>();
        jdbc.query("select user_id,email,deleted_at from users where email like ?", rs -> {
            if (rs.getTimestamp("deleted_at") != null) throw new IllegalStateException("Fixture was withdrawn; choose a new dataset");
            ids.put(rs.getString("email"), rs.getLong("user_id"));
        }, prefix + "%@fixture.invalid");
        if (ids.size() != users) throw new IllegalStateException("Dataset size differs; choose a new dataset name");
        List<Object[]> sessions = new ArrayList<>(), contexts = new ArrayList<>(), trips = new ArrayList<>();
        LocalDateTime expires = LocalDateTime.now().plusDays(7);
        List<Map<String, Object>> fixtures = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            long user = ids.get(prefix + i + "@fixture.invalid");
            for (int j = 0; j < 2; j++) {
                String sid = uuid(prefix + i + "-session-" + j);
                String hash = hasher.hash(prefix + i + "-refresh-" + j);
                sessions.add(new Object[]{sid, user, hash, expires});
            }
            String context = uuid(prefix + i + "-context"), csrf = uuid(prefix + i + "-csrf");
            contexts.add(new Object[]{hasher.hash(context), csrf, expires});
            if (i < active) {
                trips.add(new Object[]{user, "인증측정"});
                fixtures.add(Map.of("user_id", user, "cookie", "accessToken=" + issuer.issue(user, uuid(prefix + i + "-session-0"))
                        + "; CSRF_CONTEXT=" + context, "csrf", csrf,
                        "refresh_token", prefix + i + "-refresh-0", "sid", uuid(prefix + i + "-session-0"),
                        "second_cookie", "accessToken=" + issuer.issue(user, uuid(prefix + i + "-session-1")) + "; CSRF_CONTEXT=" + context,
                        "second_refresh_token", prefix + i + "-refresh-1", "second_sid", uuid(prefix + i + "-session-1")));
            }
        }
        boolean rdb = "rdb".equals(System.getProperty("load.store", "redis"));
        if (rdb) {
            jdbc.batchUpdate("insert into login_sessions(sid,user_id,refresh_token_hash,expires_at) values (?,?,?,?) on duplicate key update refresh_token_hash=values(refresh_token_hash),expires_at=values(expires_at)", sessions);
        } else {
            // Only this harness-owned dataset; no duplicate RDB session working set in B.
            jdbc.update("delete s from login_sessions s join users u on s.user_id=u.user_id where u.email like ?", prefix + "%@fixture.invalid");
            seedRedis(dataset, sessions);
        }
        long rdbRows = jdbc.queryForObject("select count(*) from login_sessions s join users u on s.user_id=u.user_id where u.email like ?", Long.class, prefix + "%@fixture.invalid");
        String csrfStorage = System.getProperty("load.csrfStore", "rdb");
        seedCsrf(dataset, contexts, csrfStorage);
        for (int i = 0; i < users; i++) {
            assertTrue(csrfStore.matches(uuid(prefix + i + "-context"), uuid(prefix + i + "-csrf")),
                    "CSRF fixture differs from the selected runtime store");
        }
        long csrfRows = csrfRows(contexts);
        int csrfKeys = rdb ? 0 : redis.keys("yeodam:performance:authperf:" + dataset + ":csrf:*").size();
        assertEquals("rdb".equals(csrfStorage) ? users : 0, csrfRows, "Unexpected CSRF RDB working set");
        assertEquals("redis".equals(csrfStorage) ? users : 0, csrfKeys, "Unexpected CSRF Redis working set");
        write(root.resolve("physical-state.json"), Map.of("rdb_session_rows", rdbRows,
                "redis_keys", rdb ? 0 : redis.keys("yeodam:performance:authperf:" + dataset + ":*").size(),
                "semantic_sessions", users * 2, "csrf_store", csrfStorage,
                "rdb_csrf_rows", csrfRows, "redis_csrf_keys", csrfKeys, "semantic_csrf", users));
        for (Object[] trip : trips) {
            Long count = jdbc.queryForObject("select count(*) from trips where user_id=? and trip_name=? and deleted_at is null", Long.class, trip);
            if (count == 0) jdbc.update("insert into trips(user_id,trip_name,start_date,end_date,processing_status) values (?,?,'2026-09-01','2026-09-02','COMPLETED')", trip);
        }
        List<Map<String, Object>> exported = new ArrayList<>();
        for (Map<String, Object> fixture : fixtures) {
            long trip = jdbc.queryForObject("select trip_id from trips where user_id=? and trip_name='인증측정' and deleted_at is null", Long.class, fixture.get("user_id"));
            jdbc.update("update trips set is_favorite=false where trip_id=?", trip);
            var row = new java.util.HashMap<>(fixture); row.put("trip_id", trip); exported.add(row);
        }
        writeSecret(root.resolve("fixtures.json"), exported);
    }
    void seedCsrf(String dataset, List<Object[]> contexts, String storage) {
        if (!java.util.Set.of("rdb", "redis").contains(storage)) {
            throw new IllegalArgumentException("Unknown CSRF fixture store");
        }
        if (storage.equals("rdb")) {
            jdbc.batchUpdate("insert into csrf_tokens(browser_context_hash,token_value,expires_at) values (?,?,?) "
                    + "on duplicate key update token_value=values(token_value),expires_at=values(expires_at)", contexts);
            return;
        }
        if (redis == null) throw new IllegalStateException("CSRF fixture requires a Redis bean");
        // Delete only these deterministic contexts, preserving other datasets.
        jdbc.batchUpdate("delete from csrf_tokens where browser_context_hash=?",
                contexts.stream().map(context -> new Object[]{context[0]}).toList());
        String prefix = "yeodam:performance:authperf:" + dataset + ":csrf:";
        for (int offset = 0; offset < contexts.size(); offset += 500) {
            List<Object[]> batch = contexts.subList(offset, Math.min(offset + 500, contexts.size()));
            redis.executePipelined(new SessionCallback<Object>() {
                @Override @SuppressWarnings("unchecked")
                public <K, V> Object execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, String> writes = (RedisOperations<String, String>) operations;
                    for (Object[] context : batch) {
                        writes.opsForValue().set(prefix + context[0], (String) context[1], java.time.Duration.ofDays(7));
                    }
                    return null;
                }
            });
        }
    }

    long csrfRows(List<Object[]> contexts) {
        long total = 0;
        for (int offset = 0; offset < contexts.size(); offset += 500) {
            List<Object[]> batch = contexts.subList(offset, Math.min(offset + 500, contexts.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            total += jdbc.queryForObject("select count(*) from csrf_tokens where browser_context_hash in ("
                    + placeholders + ")", Long.class, batch.stream().map(context -> context[0]).toArray());
        }
        return total;
    }

    void seedRedis(String dataset, List<Object[]> sessions) {
        String prefix = "yeodam:performance:authperf:" + dataset + ":";
        if (redis == null) throw new IllegalStateException("Redis fixture requires a Redis bean");
        java.util.Set<String> oldKeys = redis.keys(prefix + "*");
        if (oldKeys != null && !oldKeys.isEmpty()) redis.delete(oldKeys);
        long now = redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().time());
        java.time.Instant deadline = java.time.Instant.ofEpochMilli(now).plus(java.time.Duration.ofDays(7));
        // Fixture-only bulk preparation before HTTP traffic; no runtime Store behavior is replaced.
        // Bounded preparation batches keep the normal command timeout under the 0.5 CPU quota.
        for (int offset = 0; offset < sessions.size(); offset += 500) {
            List<Object[]> batch = sessions.subList(offset, Math.min(offset + 500, sessions.size()));
            redis.executePipelined(new SessionCallback<Object>() {
            @Override @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                RedisOperations<String, String> writes = (RedisOperations<String, String>) operations;
                for (Object[] session : batch) {
                    String sid = (String) session[0], hash = (String) session[2];
                    String key = prefix + "session:" + sid, refresh = prefix + "refresh:" + hash;
                    String index = prefix + "user-sessions:" + session[1];
                    writes.opsForHash().putAll(key, Map.of("userId", session[1].toString(),
                            "refreshTokenHash", hash, "expiresAt", Long.toString(deadline.toEpochMilli())));
                    writes.expireAt(key, deadline);
                    writes.opsForValue().set(refresh, sid);
                    writes.expireAt(refresh, deadline);
                    writes.opsForZSet().add(index, sid, deadline.toEpochMilli());
                    writes.expireAt(index, deadline);
                }
                return null;
            }
            });
        }
        for (Object[] session : sessions) {
            assertEquals(new LoginSession((Long) session[1], (String) session[2]),
                    sessionStore.findBySid((String) session[0]).orElseThrow(),
                    "Redis fixture differs from the baseline input");
        }
    }
    static String uuid(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }
    static void write(Path path, Object value) throws Exception {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, JSON.writeValueAsString(value));
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    static void writeSecret(Path path, Object value) throws Exception {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.createFile(temp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(temp, JSON.writeValueAsString(value));
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
