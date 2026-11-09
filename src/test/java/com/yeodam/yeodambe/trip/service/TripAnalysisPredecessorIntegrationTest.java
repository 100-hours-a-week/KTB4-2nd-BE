package com.yeodam.yeodambe.trip.service;

import com.sun.net.httpserver.HttpServer;
import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = "app.trip.edit-lock.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripAnalysisPredecessorIntegrationTest {
    private static final AtomicReference<Reply> REPLY = new AtomicReference<>(new Reply(404, "{}"));
    private static final HttpServer SERVER = startServer();

    @Autowired
    private TripAnalysisExecutionService scheduling;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TripEditLockProperties properties;
    @Autowired
    @Qualifier("tripEditRedissonClient")
    private RedissonClient redis;
    @MockitoSpyBean
    private TripPhotoAnalysisService remote;

    private Long tripId;
    private Long ownerId;
    private String predecessor;

    @DynamicPropertySource
    static void configureAi(DynamicPropertyRegistry registry) {
        registry.add("ai.server.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @BeforeEach
    void prepareTimedOutExecution() {
        TripAnalysisTestFixture fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
        fixture.prepareAddition(tripId, ownerId);
        predecessor = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO trip_analysis_executions
                    (execution_id, trip_id, owner_id, status, ended_at, error_code,
                     requires_remote_termination_confirmation)
                VALUES (?, ?, ?, 'FAILED', CURRENT_TIMESTAMP(6), 'AI_RESULT_TIMEOUT', TRUE)
                """, predecessor, tripId, ownerId);
        REPLY.set(new Reply(404, "{}"));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(redis.getLock(properties.namespace() + ":lock:trip:mutation:" + tripId)
                    .isHeldByCurrentThread()).isFalse();
            return invocation.callRealMethod();
        }).when(remote).findStatus(tripId, predecessor);
    }

    @Test
    void BE만_타임아웃되고_AI가_처리_중이면_새_업로드_분석도_발행하지_않는다() {
        reply("PROCESSING", predecessor);
        assertNoSuccessor();
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 503})
    void AI_상태가_없거나_연결에_실패하면_종료로_간주하지_않는다(int status) {
        REPLY.set(new Reply(status, "{}"));
        assertNoSuccessor();
    }

    @Test
    void 다른_실행의_종료_상태로_이전_실행을_종료_확인하지_않는다() {
        reply("CANCELED", UUID.randomUUID().toString());
        assertNoSuccessor();
    }

    @Test
    void 같은_실행의_원격_종료를_확인한_뒤에만_새_분석을_발행한다() {
        reply("PROCESSING", predecessor);
        assertNoSuccessor();
        reply("CANCELED", predecessor);
        assertThat(scheduling.startNext(tripId)).isPresent();
        assertThat(jdbc.queryForObject("""
                SELECT remote_terminal_confirmed_at IS NOT NULL FROM trip_analysis_executions
                WHERE execution_id = ?
                """, Boolean.class, predecessor)).isTrue();
    }

    @Test
    void 기존_구조에서_타임아웃된_분석도_후속_분석의_종료_확인_대상이다() {
        jdbc.update("DELETE FROM trip_analysis_executions WHERE execution_id = ?", predecessor);
        jdbc.update("""
                INSERT INTO additional_attachment_executions
                    (execution_id, trip_id, user_id, total_attachment_count, status,
                     input_closed, ended_at, error_code, process_published_at)
                VALUES (?, ?, ?, 1, 'FAILED', TRUE, CURRENT_TIMESTAMP(6),
                        'AI_RESULT_TIMEOUT', CURRENT_TIMESTAMP(6))
                """, predecessor, tripId, ownerId);
        reply("PROCESSING", predecessor);
        assertNoSuccessor();
        reply("CANCELED", predecessor);
        assertThat(scheduling.startNext(tripId)).isPresent();
    }

    private void assertNoSuccessor() {
        assertThat(scheduling.startNext(tripId)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM additional_attachment_upload_outbox WHERE trip_id = ?
                """, Integer.class, tripId)).isZero();
    }

    private void reply(String status, String executionId) {
        REPLY.set(new Reply(200, """
                {"trip_id":%d,"execution_id":"%s","status":"%s",
                 "progress":{"done":0,"total":1},"current_step":null,"result":null,"error":null}
                """.formatted(tripId, executionId, status)));
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/trips/", exchange -> {
                Reply reply = REPLY.get();
                byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), body.length);
                try (exchange) {
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private record Reply(
            int status,
            String body
    ) {
    }
}
