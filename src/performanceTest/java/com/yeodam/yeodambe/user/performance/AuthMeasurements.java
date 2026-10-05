package com.yeodam.yeodambe.user.performance;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Installed only by AuthPerformanceServerTest; SQL values and credentials are never recorded. */
final class AuthMeasurements implements BeanPostProcessor {
    private static final ThreadLocal<RequestSample> CURRENT = new ThreadLocal<>();
    record Totals(long count, long nanos) {
        Totals plus(long elapsed) { return new Totals(count + 1, nanos + elapsed); }
    }
    static final class RequestSample {
        String stage = "business";
        String endpoint = "unknown";
        final Map<String, Totals> stages = new HashMap<>();
        final Map<String, Totals> sql = new HashMap<>();
    }
    private volatile MeterRegistry registry;
    private BufferedWriter output;
    final AtomicInteger active = new AtomicInteger();

    synchronized void open(Path path, MeterRegistry registry) throws IOException {
        this.registry = registry;
        output = Files.newBufferedWriter(path);
        output.write("epoch_ms,phase,scenario,status,duration_ms,stage,calls,stage_ms,sql_count,sql_ms,endpoint\n");
    }
    synchronized void flush() throws IOException { if (output != null) output.flush(); }
    synchronized void close() throws IOException { if (output != null) { output.close(); output = null; } }

    @Override public Object postProcessAfterInitialization(Object bean, String name) {
        if (bean instanceof DataSource) {
            return advise(bean, invocation -> {
                if (!invocation.getMethod().getName().equals("getConnection")) return invocation.proceed();
                RequestSample sample = CURRENT.get();
                long started = System.nanoTime();
                try { return connection((Connection) invocation.proceed()); }
                finally { record(sample, "connection-acquire", System.nanoTime() - started); }
            });
        }
        if (bean instanceof org.springframework.data.redis.connection.RedisConnectionFactory) {
            return advise(bean, invocation -> {
                Object result = invocation.proceed();
                return result instanceof org.springframework.data.redis.connection.RedisConnection connection
                        ? redisCommands(connection, org.springframework.data.redis.connection.RedisConnection.class) : result;
            });
        }
        String stage = switch (name) {
            case "activeLoginSessionValidator" -> "jwt";
            case "loginSessionStore" -> "session";
            case "csrfTokenStore" -> "csrf";
            case "accessTokenRefreshService" -> "refresh";
            case "userRepository" -> "member";
            default -> null;
        };
        if (stage == null) return bean;
        return advise(bean, invocation -> {
            RequestSample sample = CURRENT.get();
            if (sample == null || (stage.equals("member") && !sample.stage.equals("jwt"))) return invocation.proceed();
            String previous = sample.stage;
            sample.stage = stage;
            long started = System.nanoTime();
            try { return invocation.proceed(); }
            finally {
                long nanos = System.nanoTime() - started;
                sample.stages.merge(stage, new Totals(1, nanos), (a, b) -> new Totals(a.count + b.count, a.nanos + b.nanos));
                if (registry != null) Timer.builder("yeodam.auth.stage").tag("stage", stage)
                        .publishPercentileHistogram().register(registry).record(nanos, TimeUnit.NANOSECONDS);
                sample.stage = previous;
            }
        });
    }
    static Object advise(Object bean, MethodInterceptor advice) {
        if (bean instanceof Advised advised && !advised.isFrozen()) { advised.addAdvice(0, advice); return bean; }
        ProxyFactory factory = new ProxyFactory(bean);
        // Keep HikariDataSource's type so Boot's pool metadata/metrics can still bind it.
        factory.setProxyTargetClass(!(bean instanceof org.springframework.data.repository.Repository));
        factory.addAdvice(advice);
        return factory.getProxy();
    }
    void record(RequestSample sample, String stage, long nanos) {
        if (sample == null) return;
        sample.stages.merge(stage, new Totals(1, nanos), (a, b) -> new Totals(a.count + b.count, a.nanos + b.nanos));
        if (registry != null) Timer.builder("yeodam.auth.stage").tag("stage", stage)
                .publishPercentileHistogram().register(registry).record(nanos, TimeUnit.NANOSECONDS);
    }
    Connection connection(Connection target) {
        RequestSample owner = CURRENT.get();
        long acquired = System.nanoTime();
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, method, args) -> {
            if (method.getName().equals("close") && closed.compareAndSet(false, true)) {
                try { return invoke(target, method, args); }
                finally { record(owner, "connection-usage", System.nanoTime() - acquired); }
            }
            Object result = invoke(target, method, args);
            if (result instanceof Statement statement && (method.getName().equals("prepareStatement") || method.getName().equals("createStatement"))) {
                Class<?> type = result instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{type}, (sp, sm, sa) -> {
                    RequestSample sample = CURRENT.get();
                    if (sample == null || !sm.getName().startsWith("execute")) return invoke(statement, sm, sa);
                    String stage = sample.stage;
                    long started = System.nanoTime();
                    try { return invoke(statement, sm, sa); }
                    finally {
                        long nanos = System.nanoTime() - started;
                        sample.sql.merge(stage, new Totals(1, nanos), (a, b) -> new Totals(a.count + b.count, a.nanos + b.nanos));
                        if (registry != null) Timer.builder("yeodam.auth.sql").tag("stage", stage)
                                .register(registry).record(nanos, TimeUnit.NANOSECONDS);
                    }
                });
            }
            return result;
        });
    }
    Object redisCommands(Object target, Class<?> type) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Class<?> returned = method.getReturnType();
            if (returned.isInterface() && returned.getPackageName().startsWith("org.springframework.data.redis.connection")
                    && returned.getSimpleName().endsWith("Commands")) {
                return redisCommands(invoke(target, method, args), returned);
            }
            String name = method.getName();
            if (java.util.Set.of("close", "isClosed", "isQueueing", "isPipelined", "getNativeConnection", "toString", "equals", "hashCode").contains(name))
                return invoke(target, method, args);
            RequestSample sample = CURRENT.get();
            long started = System.nanoTime();
            try {
                Object result = invoke(target, method, args);
                if (name.equals("exec") && result == null) record(sample, "redis-conflict", 0);
                return result;
            } finally {
                long elapsed = System.nanoTime() - started;
                record(sample, "redis-command", elapsed);
                if (sample != null && sample.stage.equals("csrf")) record(sample, "csrf-redis-command", elapsed);
            }
        });
    }
    static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
    OncePerRequestFilter filter() {
        return new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String phase = request.getHeader("X-Auth-Perf-Phase");
                String scenario = request.getHeader("X-Auth-Perf-Scenario");
                if (!java.util.Set.of("warmup", "measurement", "smoke", "drain", "fault").contains(phase == null ? "" : phase)
                        || !java.util.Set.of("S1", "S3", "S0", "MIX").contains(scenario == null ? "" : scenario)) {
                    chain.doFilter(request, response); return;
                }
                RequestSample sample = new RequestSample();
                String endpoint = request.getHeader("X-Auth-Perf-Endpoint");
                if (java.util.Set.of("place", "me", "trips", "favorite-add", "favorite-remove", "refresh").contains(endpoint == null ? "" : endpoint)) sample.endpoint = endpoint;
                CURRENT.set(sample); active.incrementAndGet();
                long started = System.nanoTime();
                try { chain.doFilter(request, response); }
                finally {
                    try { write(sample, phase, scenario, response.getStatus(), System.nanoTime() - started); }
                    finally { CURRENT.remove(); active.decrementAndGet(); }
                }
            }
        };
    }
    synchronized void write(RequestSample sample, String phase, String scenario, int status, long duration) throws IOException {
        if (output == null) return;
        long epoch = System.currentTimeMillis();
        output.write(epoch + "," + phase + "," + scenario + "," + status + "," + duration / 1e6 + ",http,1,0,0,0," + sample.endpoint + "\n");
        var stages = new java.util.HashSet<>(sample.stages.keySet()); stages.addAll(sample.sql.keySet());
        for (String stage : stages) {
            Totals timing = sample.stages.getOrDefault(stage, new Totals(0, 0));
            Totals sql = sample.sql.getOrDefault(stage, new Totals(0, 0));
            output.write(epoch + "," + phase + "," + scenario + "," + status + "," + duration / 1e6 + "," + stage
                    + "," + timing.count + "," + timing.nanos / 1e6 + "," + sql.count + "," + sql.nanos / 1e6 + "," + sample.endpoint + "\n");
        }
    }
}
