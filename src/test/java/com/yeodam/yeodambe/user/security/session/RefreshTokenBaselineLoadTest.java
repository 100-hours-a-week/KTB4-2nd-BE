package com.yeodam.yeodambe.user.security.session;

import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.security.TokenHasher;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import io.lettuce.core.TransactionResult;
import io.lettuce.core.event.command.*;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.filter.OncePerRequestFilter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=optional:file:/nonexistent-refresh-bench.properties",
        "spring.kafka.listener.auto-startup=false", "sentry.dsn=",
        "management.metrics.distribution.percentiles-histogram.http.server.requests=true"
})
@ActiveProfiles("test")
@Import(RefreshTokenBaselineLoadTest.Configuration.class)
@EnabledIfEnvironmentVariable(named = "YEODAM_REFRESH_BENCHMARK", matches = "true")
class RefreshTokenBaselineLoadTest {
    private static final String PREFIX = "yeodam:test:auth:";
    private static final ThreadLocal<Sample> CURRENT = new ThreadLocal<>();
    private static final Map<String, Sample> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> PHASE = ThreadLocal.withInitial(() -> "request");
    @Autowired UserRepository users;
    @Autowired StringRedisTemplate redis;
    @Autowired CsrfTokenStore csrf;
    @Autowired TokenHasher hasher;
    @Autowired LettuceConnectionFactory connectionFactory;
    @Autowired PrometheusMeterRegistry prometheus;
    @MockitoSpyBean LoginSessionStore sessions;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Sample> completed = new ArrayList<>();
    private final List<String> runs = new ArrayList<>();
    private final List<String> checks = new ArrayList<>();
    private final Path output = Path.of(System.getenv().getOrDefault("YEODAM_REFRESH_BENCH_OUTPUT", "/private/tmp/yeodam-refresh-283-before-20261015"));
    private final AtomicLong sequence = new AtomicLong();

    @Test
    void measureRealHttpRefreshBeforeLua() throws Exception {
        Files.createDirectories(output);
        var client = connectionFactory.getNativeClient();
        assertThat(client).isNotNull();
        var listener = new Commands();
        client.addListener(listener);
        doAnswer(invocation -> {
            Sample sample = CURRENT.get();
            String previous = PHASE.get();
            long start = System.nanoTime();
            PHASE.set("rotate");
            try { return invocation.callRealMethod(); }
            finally {
                if (sample != null) sample.rotateNanos += System.nanoTime() - start;
                PHASE.set(previous);
            }
        }).when(sessions).rotate(anyString(), anyString());
        try {
            Actor warm = actor(user());
            for (int i = 0; i < 100; i++) {
                var result = request(warm, "warmup", 0, 1, 0, i);
                assertThat(result.status).isEqualTo(200);
                warm.token = result.newToken;
            }
            checks.add("warmup_100_success=true");
            verifyContract();
            for (String scenario : List.of("independent", "shared-user")) {
                for (int concurrency : List.of(1, 5, 10)) {
                    for (int round = 1; round <= 3; round++) runChains(scenario, concurrency, round);
                }
            }
            for (int concurrency : List.of(2, 5, 10)) {
                for (int round = 1; round <= 3; round++) runRace(concurrency, round);
            }
            runRejected("expired", true);
            runRejected("reused", false);
        } finally {
            client.removeListener(listener);
            writeResults();
            http.close();
        }
    }

    private Long user() {
        return users.saveAndFlush(new User("bench283-" + UUID.randomUUID() + "@yeodam.test", "부하테스트")).getUserId();
    }

    private Actor actor(Long userId) {
        Actor actor = new Actor(userId, UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        sessions.save(actor.sid, userId, hasher.hash(actor.token));
        csrf.save(actor.context, actor.csrf);
        return actor;
    }

    private Result request(Actor actor, String scenario, int round, int concurrency, int worker, int iteration) throws Exception {
        String id = Long.toString(sequence.incrementAndGet());
        Sample sample = new Sample(id, scenario, round, concurrency, worker, iteration);
        IN_FLIGHT.put(id, sample);
        try {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/token/refresh"))
                    .timeout(Duration.ofSeconds(15)).header("X-Bench-Request", id)
                    .header("X-CSRF-TOKEN", actor.csrf)
                    .header("Cookie", "refreshToken=" + actor.token + "; CSRF_CONTEXT=" + actor.context)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            long start = System.nanoTime();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            sample.httpMs = (System.nanoTime() - start) / 1_000_000.0;
            sample.status = response.statusCode();
            sample.message = response.body().contains("TOKEN_REFRESH_SUCCESS") ? "TOKEN_REFRESH_SUCCESS" :
                    response.body().contains("REFRESH_TOKEN_INVALID_OR_EXPIRED") ? "REFRESH_TOKEN_INVALID_OR_EXPIRED" :
                    response.body().contains("AUTH_STORE_UNAVAILABLE") ? "AUTH_STORE_UNAVAILABLE" : "OTHER";
            String token = response.headers().allValues("Set-Cookie").stream()
                    .filter(value -> value.startsWith("refreshToken="))
                    .map(value -> value.substring("refreshToken=".length()).split(";", 2)[0]).findFirst().orElse(null);
            if (sample.status == 200) assertThat(token).isNotBlank();
            return new Result(sample, token, response.statusCode());
        } finally { IN_FLIGHT.remove(id); }
    }

    private void verifyContract() throws Exception {
        Actor actor = actor(user());
        String old = actor.token;
        Result rotated = request(actor, "contract", 0, 1, 0, 0);
        assertThat(rotated.status).isEqualTo(200);
        assertThat(request(actor, "contract", 0, 1, 0, 1).status).isEqualTo(401);
        actor.token = rotated.newToken;
        assertThat(sessions.findSidByRefreshTokenHash(hasher.hash(old))).isEmpty();
        assertThat(sessions.findSidByRefreshTokenHash(hasher.hash(actor.token))).contains(actor.sid);
        for (String key : List.of(PREFIX + "session:" + actor.sid, PREFIX + "refresh:" + hasher.hash(actor.token), PREFIX + "user-sessions:" + actor.userId)) {
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(604790000L, 604800000L);
        }
        sessions.deleteBySid(actor.sid);
        assertThat(request(actor, "contract", 0, 1, 0, 2).status).isEqualTo(401);
        checks.add("rotation_old_invalid_new_valid_ttl7days_logout_invalid=true");
    }

    private void runChains(String scenario, int concurrency, int round) throws Exception {
        Long sharedUser = scenario.equals("shared-user") ? user() : null;
        List<Actor> actors = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) actors.add(actor(sharedUser == null ? user() : sharedUser));
        parallel(scenario, concurrency, round, worker -> {
            List<Sample> results = new ArrayList<>();
            Actor actor = actors.get(worker);
            for (int i = 0; i < 40; i++) {
                Result result = request(actor, scenario, round, concurrency, worker, i);
                results.add(result.sample);
                if (result.status == 200) actor.token = result.newToken;
            }
            return results;
        });
        for (Actor actor : actors) sessions.deleteBySid(actor.sid);
    }

    private void runRace(int concurrency, int round) throws Exception {
        for (int wave = 0; wave < 10; wave++) {
            Actor actor = actor(user());
            int iteration = wave;
            List<Result> responses = Collections.synchronizedList(new ArrayList<>());
            parallel("same-token", concurrency, round, worker -> {
                Result result = request(actor, "same-token", round, concurrency, worker, iteration);
                responses.add(result);
                return List.of(result.sample);
            });
            assertThat(responses.stream().filter(r -> r.status == 200).count()).isEqualTo(1);
            String token = responses.stream().filter(r -> r.status == 200).findFirst().orElseThrow().newToken;
            assertThat(sessions.findSidByRefreshTokenHash(hasher.hash(token))).contains(actor.sid);
            assertThat(sessions.findSidByRefreshTokenHash(hasher.hash(actor.token))).isEmpty();
            sessions.deleteBySid(actor.sid);
        }
        checks.add("same_token_concurrency_" + concurrency + "_round_" + round + "_10waves_one_success_and_winner_retained=true");
    }

    private void runRejected(String scenario, boolean expired) throws Exception {
        Actor actor = actor(user());
        if (expired) {
            redis.expire(PREFIX + "session:" + actor.sid, Duration.ofMillis(1));
            redis.expire(PREFIX + "refresh:" + hasher.hash(actor.token), Duration.ofMillis(1));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (Boolean.TRUE.equals(redis.hasKey(PREFIX + "refresh:" + hasher.hash(actor.token))) && System.nanoTime() < deadline) Thread.sleep(2);
        } else {
            assertThat(request(actor, "prepare-reuse", 0, 1, 0, 0).status).isEqualTo(200);
        }
        parallel(scenario, 5, 1, worker -> {
            List<Sample> results = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                Result result = request(actor, scenario, 1, 5, worker, i);
                assertThat(result.status).isEqualTo(401);
                results.add(result.sample);
            }
            return results;
        });
        checks.add(scenario + "_200_requests_rejected401=true");
        sessions.deleteBySid(actor.sid);
    }

    private void parallel(String scenario, int concurrency, int round, Work work) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(concurrency + 1);
        double cpuBefore = redisCpu();
        try (var executor = Executors.newFixedThreadPool(concurrency)) {
            List<Future<List<Sample>>> tasks = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                int worker = i;
                tasks.add(executor.submit(() -> { barrier.await(); return work.run(worker); }));
            }
            long start = System.nanoTime();
            barrier.await();
            int count = 0;
            for (var task : tasks) {
                List<Sample> values = task.get(60, TimeUnit.SECONDS);
                completed.addAll(values);
                count += values.size();
            }
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            double cpuSeconds = redisCpu() - cpuBefore;
            runs.add(scenario + "," + concurrency + "," + round + "," + count + "," + seconds + "," + cpuSeconds);
        }
        System.out.println("BENCH_DONE " + scenario + " concurrency=" + concurrency + " round=" + round);
    }

    private double redisCpu() {
        Properties info = redis.execute((org.springframework.data.redis.core.RedisCallback<Properties>) connection -> connection.serverCommands().info("cpu"));
        return Double.parseDouble(info.getProperty("used_cpu_user")) + Double.parseDouble(info.getProperty("used_cpu_sys"));
    }

    private void writeResults() throws Exception {
        StringBuilder samples = new StringBuilder("id,scenario,round,concurrency,worker,iteration,status,message,http_ms,rotate_ms,commands,rotate_commands,exec_aborts,rotate_exec_aborts,attempts,rotate_attempts\n");
        StringBuilder commands = new StringBuilder("id,scenario,phase,command,count\n");
        for (Sample s : completed) {
            samples.append(String.join(",",s.id,s.scenario,""+s.round,""+s.concurrency,""+s.worker,""+s.iteration,""+s.status,s.message,""+s.httpMs,""+(s.rotateNanos/1_000_000.0),""+s.commands.get(),""+s.rotateCommands.get(),""+s.aborts.get(),""+s.rotateAborts.get(),""+s.attempts.get(),""+s.rotateAttempts.get())).append('\n');
            s.counts.forEach((key,count) -> commands.append(s.id).append(',').append(s.scenario).append(',').append(key).append(',').append(count.get()).append('\n'));
        }
        Files.writeString(output.resolve("samples.csv"),samples);
        Files.writeString(output.resolve("commands.csv"),commands);
        Files.writeString(output.resolve("runs.csv"),"scenario,concurrency,round,requests,wall_seconds,redis_cpu_seconds\n"+String.join("\n",runs));
        Files.writeString(output.resolve("checks.txt"),String.join("\n",checks));
        Files.writeString(output.resolve("prometheus-final.txt"),prometheus.scrape());
        assertThat(completed).isNotEmpty();
        assertThat(completed.stream().allMatch(s -> s.commands.get()>0)).isTrue();
    }

    static class Commands implements CommandListener {
        @Override public void commandStarted(CommandStartedEvent event) {
            Sample sample = CURRENT.get();
            if (sample == null) return;
            String name = event.getCommand().getType().toString();
            boolean rotating = PHASE.get().equals("rotate");
            event.getContext().put("refreshBenchSample", sample);
            event.getContext().put("refreshBenchRotate", rotating);
            sample.commands.incrementAndGet();
            if (rotating) sample.rotateCommands.incrementAndGet();
            sample.counts.computeIfAbsent((rotating ? "rotate," : "request,") + name, ignored -> new AtomicInteger()).incrementAndGet();
            if (name.equals("WATCH")) {
                var firstKey = event.getCommand().getArgs().getFirstEncodedKey();
                if (firstKey != null && java.nio.charset.StandardCharsets.UTF_8.decode(firstKey.duplicate()).toString().startsWith(PREFIX + "refresh:")) {
                    sample.attempts.incrementAndGet();
                    if (rotating) sample.rotateAttempts.incrementAndGet();
                }
            }
        }
        @Override public void commandSucceeded(CommandSucceededEvent event) {
            Sample s = (Sample) event.getContext().get("refreshBenchSample");
            if (s == null || !event.getCommand().getType().toString().equals("EXEC")) return;
            Object value = event.getCommand().getOutput().get();
            if (value instanceof TransactionResult result && result.wasDiscarded()) {
                s.aborts.incrementAndGet();
                if (Boolean.TRUE.equals(event.getContext().get("refreshBenchRotate"))) s.rotateAborts.incrementAndGet();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @ServiceConnection MySQLContainer mysql() {
            return new MySQLContainer("mysql:9.7.2").withCreateContainerCmdModifier(command -> command.getHostConfig().withNanoCPUs(2_000_000_000L).withMemory(1_073_741_824L));
        }
        @Bean @ServiceConnection(name="redis") GenericContainer<?> redis() {
            return new GenericContainer<>("redis:8.2.9-alpine").withExposedPorts(6379)
                    .withCreateContainerCmdModifier(command -> command.getHostConfig().withNanoCPUs(2_000_000_000L).withMemory(536_870_912L));
        }
        @Bean ScopeFilter benchmarkScope() {
            return new ScopeFilter();
        }
    }

    static class ScopeFilter extends OncePerRequestFilter implements Ordered {
        @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws java.io.IOException,jakarta.servlet.ServletException {
            String id=request.getHeader("X-Bench-Request");
            Sample sample=id==null ? null : IN_FLIGHT.get(id);
            if (sample!=null) CURRENT.set(sample);
            try { chain.doFilter(request,response); }
            finally { CURRENT.remove(); PHASE.remove(); }
        }
    }

    interface Work { List<Sample> run(int worker) throws Exception; }
    record Result(Sample sample,String newToken,int status) {}
    static class Actor {
        final Long userId; final String sid,context,csrf; String token;
        Actor(Long userId,String sid,String token,String context,String csrf) { this.userId=userId;this.sid=sid;this.token=token;this.context=context;this.csrf=csrf; }
    }
    static class Sample {
        final String id,scenario; final int round,concurrency,worker,iteration;
        int status; String message; double httpMs; long rotateNanos;
        final AtomicInteger commands=new AtomicInteger(),rotateCommands=new AtomicInteger(),aborts=new AtomicInteger(),rotateAborts=new AtomicInteger(),attempts=new AtomicInteger(),rotateAttempts=new AtomicInteger();
        final Map<String,AtomicInteger> counts=new ConcurrentHashMap<>();
        Sample(String id,String scenario,int round,int concurrency,int worker,int iteration) { this.id=id;this.scenario=scenario;this.round=round;this.concurrency=concurrency;this.worker=worker;this.iteration=iteration; }
    }
}
