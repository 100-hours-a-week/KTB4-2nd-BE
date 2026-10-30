package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "auth.websocket.internal-service-token=permission-performance-test",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@EnabledIfSystemProperty(named = "trip.permission.performance.enabled", matches = "true")
class TripPermissionAccessPerformanceTest {
    @LocalServerPort private int port;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripShareUserRepository shares;
    @Autowired private LoginSessionStore sessions;
    @Autowired private AccessTokenIssuer tokens;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private DataSource dataSource;
    @Autowired private MeterRegistry meters;
    @Autowired private ObjectMapper mapper;

    @Test
    void 실제_인증_접근을_동시_요청_1개와_10개로_비교한다() throws Exception {
        List<Identity> identities = seed();
        List<HttpRequest> requests = new ArrayList<>();
        for (Identity identity : identities) {
            String sid = UUID.randomUUID().toString();
            sessions.save(sid, identity.userId(), UUID.randomUUID().toString());
            requests.add(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                            + "/api/internal/websocket/trips/" + identity.tripId() + "/access"))
                    .header("X-Internal-Service-Token", "permission-performance-test")
                    .header("Cookie", "accessToken=" + tokens.issue(identity.userId(), sid))
                    .timeout(Duration.ofSeconds(2)).build());
        }
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        List<Map<String, Object>> results = new ArrayList<>();
        Path report = Path.of("build/reports/performance/trip-permission-access.json");
        Files.createDirectories(report.getParent());
        try (HttpClient http = HttpClient.newHttpClient()) {
            for (HttpRequest warmup : requests.subList(0, 20)) {
                assertThat(http.send(warmup, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            }
            Timer acquisition = meters.find("hikaricp.connections.acquire").tag("pool", pool.getPoolName()).timer();
            assertThat(acquisition).as("actual Hikari acquisition metric").isNotNull();
            for (int groups : List.of(100, 500, 1_000)) {
                for (int concurrency : List.of(1, 10)) {
                    results.add(measure(http, requests.subList(0, groups), concurrency, pool, statistics, acquisition));
                    Files.writeString(report, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results));
                }
            }
        }
        assertThat(results).hasSize(6);
    }

    private Map<String, Object> measure(
            HttpClient http, List<HttpRequest> requests, int concurrency,
            HikariDataSource pool, Statistics statistics, Timer acquisition
    ) throws Exception {
        AtomicInteger peakWaiting = new AtomicInteger();
        AtomicInteger peakActive = new AtomicInteger();
        AtomicInteger successful = new AtomicInteger();
        ConcurrentLinkedQueue<Long> durations = new ConcurrentLinkedQueue<>();
        long previousSql = statistics.getPrepareStatementCount();
        long previousAcquires = acquisition.count();
        double previousAcquisitionMillis = acquisition.totalTime(TimeUnit.MILLISECONDS);
        long started = System.nanoTime();
        try (ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
             ExecutorService workers = Executors.newFixedThreadPool(concurrency)) {
            sampler.scheduleAtFixedRate(() -> {
                peakWaiting.accumulateAndGet(pool.getHikariPoolMXBean().getThreadsAwaitingConnection(), Math::max);
                peakActive.accumulateAndGet(pool.getHikariPoolMXBean().getActiveConnections(), Math::max);
            }, 0, 1, TimeUnit.MILLISECONDS);
            List<Future<?>> pending = new ArrayList<>();
            for (HttpRequest request : requests) {
                pending.add(workers.submit(() -> {
                    long requestStarted = System.nanoTime();
                    try {
                        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                        assertThat(response.statusCode()).isEqualTo(200);
                        JsonNode body = mapper.readTree(response.body());
                        assertThat(body.path("message").asString()).isEqualTo("TRIP_WEBSOCKET_ACCESS_FOUND");
                        assertThat(body.path("data").path("permission").asString()).isEqualTo("READ");
                        successful.incrementAndGet();
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    } finally {
                        durations.add(System.nanoTime() - requestStarted);
                    }
                }));
            }
            for (Future<?> request : pending) {
                request.get(30, TimeUnit.SECONDS);
            }
            sampler.shutdownNow();
        }
        double elapsedMillis = (System.nanoTime() - started) / 1_000_000.0;
        List<Long> ordered = durations.stream().sorted().toList();
        long acquireCount = acquisition.count() - previousAcquires;
        double acquireMillis = acquisition.totalTime(TimeUnit.MILLISECONDS) - previousAcquisitionMillis;
        assertThat(successful.get()).isEqualTo(requests.size());
        assertThat(acquireCount).isPositive();
        return Map.ofEntries(
                Map.entry("groups", requests.size()), Map.entry("concurrency", concurrency),
                Map.entry("successful_http", successful.get()), Map.entry("elapsed_ms", elapsedMillis),
                Map.entry("http_p95_ms", ordered.get((int) Math.ceil(ordered.size() * 0.95) - 1) / 1_000_000.0),
                Map.entry("prepared_statements_including_background", statistics.getPrepareStatementCount() - previousSql),
                Map.entry("connection_acquisitions", acquireCount), Map.entry("connection_acquire_total_ms", acquireMillis),
                Map.entry("connection_acquire_mean_ms", acquireMillis / acquireCount),
                Map.entry("peak_threads_waiting_for_connection", peakWaiting.get()),
                Map.entry("peak_active_connections", peakActive.get()), Map.entry("pool_maximum", pool.getMaximumPoolSize()));
    }

    private List<Identity> seed() {
        return new TransactionTemplate(transactions).execute(status -> {
            User owner = users.save(new User(UUID.randomUUID() + "@permission-perf.test", "소유자"));
            List<Identity> identities = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
            for (int index = 1; index <= 1_000; index++) {
                User actor = users.save(new User(UUID.randomUUID() + "@permission-perf.test", "공유자"));
                Trip trip = new Trip(owner.getUserId(), "검사" + index, LocalDate.now(), LocalDate.now());
                ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
                trips.save(trip);
                shares.save(new TripShareUser(trip.getId(), owner.getUserId(), actor.getUserId(),
                        TripSharePermission.READ, now.plusHours(2), now));
                identities.add(new Identity(actor.getUserId(), trip.getId()));
            }
            return identities;
        });
    }

    private record Identity(
            Long userId,
            Long tripId
    ) {
    }
}
