package com.yeodam.websocket.common.websocket.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yeodam.websocket.common.websocket.service.RedisSubscriptionState;
import com.yeodam.websocket.common.websocket.session.WebSocketSessionManager;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WebSocketStatusLoggerTest {

    @Test
    void 현재_개수를_숫자로_구독_준비_상태를_불리언으로_기록한다() {
        var sessions = mock(WebSocketSessionManager.class);
        var users = mock(SimpUserRegistry.class);
        var state = new RedisSubscriptionState();
        var statusLogger = new WebSocketStatusLogger(sessions, users, state);
        when(sessions.getSessions()).thenReturn(List.of());
        when(users.getUsers()).thenReturn(Set.of());
        Logger logger = (Logger) LoggerFactory.getLogger(WebSocketStatusLogger.class);
        Level previousLevel = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            statusLogger.logStatus();
            var user = mock(SimpUser.class);
            var session = mock(SimpSession.class);
            when(user.getSessions()).thenReturn(Set.of(session));
            when(session.getSubscriptions()).thenReturn(Set.of(
                    mock(SimpSubscription.class), mock(SimpSubscription.class)));
            when(users.getUsers()).thenReturn(Set.of(user));
            when(sessions.getSessions()).thenReturn(List.of(
                    mock(org.springframework.web.socket.WebSocketSession.class)));
            state.markSubscribed();
            statusLogger.logStatus();

            assertThat(appender.list).hasSize(2);
            var converter = new ThrowableProxyConverter();
            converter.start();
            var formatter = new WebSocketJsonLogFormatter(new MockEnvironment(), converter);
            var mapper = new ObjectMapper();
            var first = mapper.readTree(formatter.format(appender.list.get(0)));
            var second = mapper.readTree(formatter.format(appender.list.get(1)));
            assertThat(first.path("event").asString()).isEqualTo("websocket_status");
            assertThat(first.path("connections").asInt()).isZero();
            assertThat(first.path("redis_subscription_ready").asBoolean()).isFalse();
            assertThat(second.path("connections").isNumber()).isTrue();
            assertThat(second.path("connections").asInt()).isEqualTo(1);
            assertThat(second.path("subscriptions").isNumber()).isTrue();
            assertThat(second.path("subscriptions").asInt()).isEqualTo(2);
            assertThat(second.path("redis_subscription_ready").isBoolean()).isTrue();
            assertThat(second.path("redis_subscription_ready").asBoolean()).isTrue();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void 최초_60초_후부터_60초_fixedDelay로_상태를_기록한다() throws Exception {
        var scheduled = WebSocketStatusLogger.class.getMethod("logStatus").getAnnotation(Scheduled.class);
        assertThat(scheduled.scheduler()).isEqualTo("webSocketSessionTaskScheduler");
        assertThat(scheduled.initialDelay()).isEqualTo(60_000);
        assertThat(scheduled.fixedDelay()).isEqualTo(60_000);
    }
}
