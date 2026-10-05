package com.yeodam.yeodambe.user.performance;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class AuthMeasurementsTest {
    @Test void attributesRedisCommandsToCsrfWithoutRecordingValues() throws Exception {
        var measurements = new AuthMeasurements();
        var file = Files.createTempFile("auth-csrf", ".csv");
        var raw = org.mockito.Mockito.mock(org.springframework.data.redis.connection.RedisConnection.class);
        var connection = (org.springframework.data.redis.connection.RedisConnection)
                measurements.redisCommands(raw, org.springframework.data.redis.connection.RedisConnection.class);
        var store = org.mockito.Mockito.mock(com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore.class);
        org.mockito.Mockito.when(store.matches("context-secret", "token-secret")).thenAnswer(invocation -> {
            connection.get("key-secret".getBytes());
            return true;
        });
        var measured = (com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore)
                measurements.postProcessAfterInitialization(store, "csrfTokenStore");
        try {
            measurements.open(file, new SimpleMeterRegistry());
            var request = new MockHttpServletRequest();
            request.addHeader("X-Auth-Perf-Phase", "measurement");
            request.addHeader("X-Auth-Perf-Scenario", "MIX");
            request.addHeader("X-Auth-Perf-Endpoint", "favorite-add");
            measurements.filter().doFilter(request, new MockHttpServletResponse(),
                    (a, b) -> assertTrue(measured.matches("context-secret", "token-secret")));
            measurements.flush();
            String csv = Files.readString(file);
            assertTrue(csv.contains(",csrf,1,"));
            assertTrue(csv.contains(",csrf-redis-command,1,"));
            assertFalse(csv.contains("secret"));
        } finally { measurements.close(); Files.deleteIfExists(file); }
    }

    @Test void recordsConnectionAcquisitionInTheRequestPhase() throws Exception {
        var measurements=new AuthMeasurements();
        var file=Files.createTempFile("auth-acquire", ".csv");
        try (var pool=new com.zaxxer.hikari.HikariDataSource()) {
            pool.setJdbcUrl("jdbc:h2:mem:auth_acquire");
            var source=(javax.sql.DataSource)measurements.postProcessAfterInitialization(pool,"dataSource");
            measurements.open(file,new SimpleMeterRegistry());
            var request=new MockHttpServletRequest();
            request.addHeader("X-Auth-Perf-Phase","measurement");request.addHeader("X-Auth-Perf-Scenario","MIX");
            measurements.filter().doFilter(request,new MockHttpServletResponse(),(a,b)->{
                try (var connection=source.getConnection();var statement=connection.createStatement()) {
                    statement.execute("select 1");
                } catch(java.sql.SQLException e){throw new jakarta.servlet.ServletException(e);}
            });
            measurements.flush();
            assertTrue(Files.readString(file).contains(",connection-acquire,1,"));
            assertTrue(Files.readString(file).contains(",connection-usage,1,"));
        } finally { measurements.close();Files.deleteIfExists(file); }
    }
    @Test void recordsRedisExecCancellationSeparatelyFromCommands() throws Exception {
        var measurements=new AuthMeasurements();
        var file=Files.createTempFile("auth-redis", ".csv");
        var raw=org.mockito.Mockito.mock(org.springframework.data.redis.connection.RedisConnection.class);
        org.mockito.Mockito.when(raw.exec()).thenReturn(null);
        var connection=(org.springframework.data.redis.connection.RedisConnection)measurements.redisCommands(raw,org.springframework.data.redis.connection.RedisConnection.class);
        try {
            measurements.open(file,new SimpleMeterRegistry());
            var request=new MockHttpServletRequest();
            request.addHeader("X-Auth-Perf-Phase","measurement");request.addHeader("X-Auth-Perf-Scenario","MIX");
            measurements.filter().doFilter(request,new MockHttpServletResponse(),(a,b)->{
                connection.watch("test".getBytes());assertNull(connection.exec());
            });
            measurements.flush();
            assertTrue(Files.readString(file).contains(",redis-command,2,"));
            assertTrue(Files.readString(file).contains(",redis-conflict,1,"));
            connection.exec();measurements.flush();
            assertEquals(4,Files.readAllLines(file).size());
        } finally {measurements.close();Files.deleteIfExists(file);}
    }

    @Test void countsExecutedSqlButNotPreparationOrUnmarkedRequests() throws Exception {
        var measurements = new AuthMeasurements();
        var file = Files.createTempFile("auth-sql", ".csv");
        try (var raw = java.sql.DriverManager.getConnection("jdbc:h2:mem:auth_probe")) {
            measurements.open(file, new SimpleMeterRegistry());
            var connection = measurements.connection(raw);
            var request = new MockHttpServletRequest();
            request.addHeader("X-Auth-Perf-Phase", "measurement");
            request.addHeader("X-Auth-Perf-Scenario", "S1");
            measurements.filter().doFilter(request, new MockHttpServletResponse(), (a, b) -> {
                try (var unused = connection.prepareStatement("select 3");
                     var statement = connection.prepareStatement("select 1")) {
                    try (var result = statement.executeQuery()) { assertTrue(result.next()); assertEquals(1, result.getInt(1)); }
                    try (var result = statement.executeQuery()) { assertTrue(result.next()); }
                } catch (java.sql.SQLException error) { throw new jakarta.servlet.ServletException(error); }
            });
            try (var statement = connection.prepareStatement("select 2")) { statement.executeQuery().close(); }
            measurements.flush();
            var lines = Files.readAllLines(file);
            assertEquals(3, lines.size());
            assertTrue(lines.get(2).contains(",business,0,0.0,2,"));
        } finally { measurements.close(); Files.deleteIfExists(file); }
    }
    @Test void recordsRejectedRequestsAndClearsContextAfterException() throws Exception {
        var measurements = new AuthMeasurements();
        var file = Files.createTempFile("auth-measurement", ".csv");
        try {
            measurements.open(file, new SimpleMeterRegistry());
            var request = new MockHttpServletRequest();
            request.addHeader("X-Auth-Perf-Phase", "measurement");
            request.addHeader("X-Auth-Perf-Scenario", "S1");
            var response = new MockHttpServletResponse(); response.setStatus(401);
            assertThrows(jakarta.servlet.ServletException.class, () -> measurements.filter().doFilter(request, response,
                    (a, b) -> { throw new jakarta.servlet.ServletException("expected"); }));
            assertEquals(0, measurements.active.get());
            measurements.filter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (a, b) -> {});
            measurements.flush();
            var lines = Files.readAllLines(file);
            assertEquals(2, lines.size());
            assertTrue(lines.get(1).contains(",measurement,S1,401,"));
        } finally { measurements.close(); Files.deleteIfExists(file); }
    }
    @Test void recordsMixedLoadRequests() throws Exception {
        var measurements = new AuthMeasurements();
        var file = Files.createTempFile("auth-mixed", ".csv");
        try {
            measurements.open(file, new SimpleMeterRegistry());
            var request = new MockHttpServletRequest();
            request.addHeader("X-Auth-Perf-Phase", "measurement");
            request.addHeader("X-Auth-Perf-Scenario", "MIX");
            measurements.filter().doFilter(request, new MockHttpServletResponse(), (a, b) -> {});
            measurements.flush();
            assertEquals(2, Files.readAllLines(file).size());
        } finally { measurements.close(); Files.deleteIfExists(file); }
    }
}
