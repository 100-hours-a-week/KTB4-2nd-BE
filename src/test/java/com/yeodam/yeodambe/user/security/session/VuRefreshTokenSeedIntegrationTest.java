package com.yeodam.yeodambe.user.security.session;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.user.entity.LoginSessionEntity;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.common.exception.RefreshTokenInvalidOrExpiredException;
import com.yeodam.yeodambe.user.repository.LoginSessionRepository;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.service.AccessTokenRefreshService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class VuRefreshTokenSeedIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private LoginSessionRepository sessions;
    @Autowired private LoginSessionStore redisSessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private LoginSessionIssuer issuer;
    @Autowired private AccessTokenRefreshService refreshService;
    @Autowired private DataSource dataSource;
    @TempDir Path temporary;

    @Test
    void generatedTokensRotateAndCleanupPreservesOtherSessionsAndUsers() throws Exception {
        List<User> owners = saveUsers();
        IssuedLoginSession existing = issuer.issue(owners.getFirst().getUserId());
        Path output = generate(owners.stream().map(User::getUserId).toList());
        applySeed(output, 7);

        JsonNode tokens = new ObjectMapper().readTree(output.resolve("tokens.json").toFile()).path("tokens");
        assertThat(tokens.size()).isEqualTo(7);
        assertThat(runRedisScript(output.resolve("seed.redis.lua"))).isEqualTo(7);
        for (JsonNode token : tokens) {
            String sid = token.path("sid").asString();
            String original = token.path("refreshToken").asString();
            LoginSessionEntity seeded = sessions.findBySid(sid).orElseThrow();
            assertThat(seeded.getExpiresAt()).isEqualTo(seeded.getCreatedAt().plusDays(7));
            AccessTokenRefreshService.Result rotated = refreshService.refresh(original);
            assertThat(rotated.accessToken()).isNotBlank();
            assertThatThrownBy(() -> refreshService.refresh(original))
                    .isInstanceOf(RefreshTokenInvalidOrExpiredException.class);
            assertThat(refreshService.refresh(rotated.refreshToken()).accessToken()).isNotBlank();
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            executeScript(connection, output.resolve("cleanup.sql"));
            connection.commit();
        }
        assertThat(runRedisScript(output.resolve("cleanup.redis.lua"))).isEqualTo(7);
        for (JsonNode token : tokens) {
            assertThat(redisSessions.findBySid(token.path("sid").asString())).isEmpty();
            assertThat(sessions.findBySid(token.path("sid").asString())).isEmpty();
        }
        assertThat(redisSessions.findBySid(existing.sid())).isPresent();
        assertThat(users.findAllById(owners.stream().map(User::getUserId).toList())).hasSize(7);
    }

    @Test
    void missingUserPreventsAllSevenSessionInserts() throws Exception {
        List<Long> ids = new ArrayList<>(saveUsers().stream().map(User::getUserId).toList());
        ids.set(6, Long.MAX_VALUE);
        applySeed(generate(ids), 0);
    }

    @Test
    void deletedUserPreventsAllSevenSessionInserts() throws Exception {
        List<User> owners = saveUsers();
        User deleted = owners.getLast();
        deleted.withdraw(LocalDateTime.now());
        users.saveAndFlush(deleted);
        applySeed(generate(owners.stream().map(User::getUserId).toList()), 0);
    }

    private List<User> saveUsers() {
        List<User> owners = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            owners.add(users.saveAndFlush(new User(UUID.randomUUID() + "@yeodam.test", "VU회원")));
        }
        return owners;
    }

    private Path generate(List<Long> userIds) throws Exception {
        Path output = temporary.resolve(UUID.randomUUID().toString());
        List<String> command = new ArrayList<>(List.of(
                "python3", "tools/generate_vu_refresh_tokens.py", "--user-ids"));
        userIds.forEach(id -> command.add(id.toString()));
        command.addAll(List.of("--output-dir", output.toString()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertThat(finished).isTrue();
        assertThat(process.exitValue()).isZero();
        return output;
    }

    private void applySeed(Path output, int expectedCount) throws Exception {
        JsonNode tokens = new ObjectMapper().readTree(output.resolve("tokens.json").toFile()).path("tokens");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                // Match the application's LocalDateTime convention for expiration checks.
                statement.execute("SET time_zone = '" + OffsetDateTime.now().getOffset().getId()
                        .replace("Z", "+00:00") + "'");
            }
            executeScript(connection, output.resolve("seed.sql"));
            int count = 0;
            for (JsonNode token : tokens) {
                try (var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM login_sessions WHERE sid = ?")) {
                    statement.setString(1, token.path("sid").asString());
                    try (var result = statement.executeQuery()) {
                        result.next();
                        count += result.getInt(1);
                    }
                }
            }
            assertThat(count).isEqualTo(expectedCount);
            if (expectedCount == 7) {
                connection.commit();
            } else {
                connection.rollback();
            }
        }
    }

    private Long runRedisScript(Path path) throws Exception {
        return redis.execute(RedisScript.of(Files.readString(path), Long.class), List.of(), "yeodam:test:auth:");
    }

    private void executeScript(Connection connection, Path script) throws Exception {
        try (var statement = connection.createStatement()) {
            for (String sql : Files.readString(script).split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }
}
