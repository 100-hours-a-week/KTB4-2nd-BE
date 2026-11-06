package com.yeodam.yeodambe.migrationprobe.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yeodam.yeodambe.migrationprobe.exception.MigrationProbeHashConflictException;
import com.yeodam.yeodambe.migrationprobe.exception.MigrationProbeStoreUnavailableException;
import com.yeodam.yeodambe.migrationprobe.repository.MigrationProbeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MigrationProbeServiceTest {

    private static final String RUN = "00000000-0000-4000-8000-000000000001";
    private static final String REQUEST = "00000000-0000-4000-8000-000000000002";
    private static final String HASH = "a".repeat(64);

    private final MigrationProbeRepository repository = mock(MigrationProbeRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(MigrationProbeService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private MigrationProbeService service;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        service = new MigrationProbeService(repository, transactions);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void 추가_데이터를_커밋하면_생성_결과를_반환한다() {
        doAnswer(invocation -> {
            assertThat(appender.list).isEmpty();
            return null;
        }).when(transactions).commit(any());

        assertThat(service.write(RUN, REQUEST, HASH).result()).isEqualTo("created");
        var order = inOrder(repository, transactions);
        order.verify(repository).insert(RUN, REQUEST, HASH);
        order.verify(transactions).commit(any());
        assertThat(appender.list).hasSize(1);
        assertThat(value(appender.list.getFirst(), "commit_result")).isEqualTo("committed");
    }

    @Test
    void 같은_해시는_롤백_후_새_트랜잭션에서_기존_데이터_결과를_반환한다() {
        duplicateInsert();
        when(repository.findPayloadHash(RUN, REQUEST)).thenReturn(Optional.of(HASH));

        assertThat(service.write(RUN, REQUEST, HASH).result()).isEqualTo("already_exists");
        var order = inOrder(transactions, repository);
        order.verify(transactions).getTransaction(any());
        order.verify(repository).insert(RUN, REQUEST, HASH);
        order.verify(transactions).rollback(any());
        order.verify(transactions).getTransaction(any());
        order.verify(repository).findPayloadHash(RUN, REQUEST);
        order.verify(transactions).commit(any());
        var definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions, times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues()).allSatisfy(definition ->
                assertThat(definition.getPropagationBehavior())
                        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void 다른_해시는_조회_트랜잭션을_끝낸_뒤_거부한다() {
        duplicateInsert();
        when(repository.findPayloadHash(RUN, REQUEST)).thenReturn(Optional.of("b".repeat(64)));

        assertThatThrownBy(() -> service.write(RUN, REQUEST, HASH))
                .isInstanceOf(MigrationProbeHashConflictException.class);
        verify(transactions).commit(any());
        assertThat(value(appender.list.getFirst(), "result")).isEqualTo("conflict");
    }

    @Test
    void 중복_오류_후_행이_없으면_성공을_반환하지_않는다() {
        duplicateInsert();
        when(repository.findPayloadHash(RUN, REQUEST)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.write(RUN, REQUEST, HASH))
                .isInstanceOf(MigrationProbeStoreUnavailableException.class);
        assertNoSuccess();
    }

    @Test
    void 추가_실패는_재시도하지_않고_예외_메시지를_노출하지_않는다() {
        doThrow(new DataAccessResourceFailureException("jdbc:mysql://secret-host?password=SECRET"))
                .when(repository).insert(RUN, REQUEST, HASH);

        assertThatThrownBy(() -> service.write(RUN, REQUEST, HASH))
                .isInstanceOf(MigrationProbeStoreUnavailableException.class);
        verify(repository).insert(RUN, REQUEST, HASH);
        verify(repository, never()).findPayloadHash(any(), any());
        assertNoSuccess();
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("SECRET", "secret-host");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void 커밋의_중복_오류를_추가의_중복_오류로_처리하지_않는다() {
        doThrow(new DuplicateKeyException("commit failure")).when(transactions).commit(any());

        assertThatThrownBy(() -> service.write(RUN, REQUEST, HASH))
                .isInstanceOf(MigrationProbeStoreUnavailableException.class);
        verify(repository, never()).findPayloadHash(any(), any());
        assertThat(value(appender.list.getFirst(), "failure_stage")).isEqualTo("commit");
        assertNoSuccess();
    }

    @Test
    void 커밋_결과가_불분명하면_생성_결과를_반환하지_않는다() {
        doThrow(new TransactionSystemException("unknown commit result"))
                .when(transactions).commit(any());

        assertThatThrownBy(() -> service.write(RUN, REQUEST, HASH))
                .isInstanceOf(MigrationProbeStoreUnavailableException.class);
        assertThat(value(appender.list.getFirst(), "commit_result")).isEqualTo("unknown");
        assertNoSuccess();
    }

    @Test
    void 조회에_실패하면_기존_데이터_결과를_반환하지_않는다() {
        duplicateInsert();
        when(repository.findPayloadHash(RUN, REQUEST))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));

        assertThatThrownBy(() -> service.write(RUN, REQUEST, HASH))
                .isInstanceOf(MigrationProbeStoreUnavailableException.class);
        assertNoSuccess();
    }

    @Test
    void 조회_커밋에_실패하면_기존_데이터_결과를_반환하지_않는다() {
        duplicateInsert();
        when(repository.findPayloadHash(RUN, REQUEST)).thenReturn(Optional.of(HASH));
        doThrow(new TransactionSystemException("unknown read commit"))
                .when(transactions).commit(any());

        assertThatThrownBy(() -> service.write(RUN, REQUEST, HASH))
                .isInstanceOf(MigrationProbeStoreUnavailableException.class);
        assertNoSuccess();
    }

    @Test
    void 롤백_실패의_콜백_원인으로_원본_DB_예외를_노출하지_않는다() {
        var transactionLogger = (Logger) LoggerFactory.getLogger(
                "org.springframework.transaction.support.TransactionTemplate");
        var rollbackLogs = new ListAppender<ILoggingEvent>();
        rollbackLogs.start();
        transactionLogger.addAppender(rollbackLogs);
        try {
            doThrow(new DataAccessResourceFailureException("password=SECRET_DATABASE_VALUE"))
                    .when(repository).insert(RUN, REQUEST, HASH);
            doThrow(new TransactionSystemException("rollback unavailable"))
                    .when(transactions).rollback(any());

            assertThatThrownBy(() -> service.write(RUN, REQUEST, HASH))
                    .isInstanceOf(MigrationProbeStoreUnavailableException.class);
            assertThat(rollbackLogs.list).isNotEmpty();
            assertThat(rollbackLogs.list).allSatisfy(event -> {
                var converter = new ch.qos.logback.classic.pattern.ThrowableProxyConverter();
                converter.start();
                assertThat(converter.convert(event)).doesNotContain("SECRET_DATABASE_VALUE");
            });
        } finally {
            transactionLogger.detachAppender(rollbackLogs);
            rollbackLogs.stop();
        }
    }

    @Test
    void 중복_오류의_롤백에_실패해도_원본_DB_예외를_노출하지_않는다() {
        var transactionLogger = (Logger) LoggerFactory.getLogger(
                "org.springframework.transaction.support.TransactionTemplate");
        var rollbackLogs = new ListAppender<ILoggingEvent>();
        rollbackLogs.start();
        transactionLogger.addAppender(rollbackLogs);
        try {
            doThrow(new DuplicateKeyException("payload_hash=SECRET_DATABASE_VALUE"))
                    .when(repository).insert(RUN, REQUEST, HASH);
            doThrow(new TransactionSystemException("rollback unavailable"))
                    .when(transactions).rollback(any());

            assertThatThrownBy(() -> service.write(RUN, REQUEST, HASH))
                    .isInstanceOf(MigrationProbeStoreUnavailableException.class);
            verify(repository, never()).findPayloadHash(any(), any());
            assertThat(rollbackLogs.list).isNotEmpty();
            assertThat(rollbackLogs.list).allSatisfy(event -> {
                var converter = new ch.qos.logback.classic.pattern.ThrowableProxyConverter();
                converter.start();
                assertThat(converter.convert(event)).doesNotContain("SECRET_DATABASE_VALUE");
            });
        } finally {
            transactionLogger.detachAppender(rollbackLogs);
            rollbackLogs.stop();
        }
    }

    private void duplicateInsert() {
        doThrow(new DuplicateKeyException("duplicate"))
                .when(repository).insert(RUN, REQUEST, HASH);
    }

    private void assertNoSuccess() {
        assertThat(appender.list).hasSize(1);
        assertThat(value(appender.list.getFirst(), "result")).isEqualTo("failure");
    }

    private Object value(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> pair.key.equals(key))
                .map(pair -> pair.value)
                .findFirst()
                .orElse(null);
    }
}
