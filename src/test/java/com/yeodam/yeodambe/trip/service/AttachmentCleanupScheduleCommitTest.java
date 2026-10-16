package com.yeodam.yeodambe.trip.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class AttachmentCleanupScheduleCommitTest {
    private final AttachmentCleanupScheduleStore store = spy(
            new AttachmentCleanupScheduleStore(mock(StringRedisTemplate.class)));
    private final Instant scheduledAt = Instant.parse("2026-11-15T00:00:00Z");

    @BeforeEach
    void prepare() {
        doNothing().when(store).schedule(anyLong(), any());
    }

    @Test
    void 커밋_전에는_등록하지_않고_커밋_후_전달받은_ID를_등록한다() {
        var ids = new ArrayList<>(List.of(10L, 20L));
        transactions().executeWithoutResult(status -> {
            store.scheduleAfterCommit(ids, scheduledAt);
            ids.clear();
            verify(store, never()).schedule(anyLong(), any());
        });

        verify(store).schedule(10L, scheduledAt);
        verify(store).schedule(20L, scheduledAt);
    }

    @Test
    void 롤백하면_예약을_등록하지_않는다() {
        transactions().executeWithoutResult(status -> {
            store.scheduleAfterCommit(List.of(10L), scheduledAt);
            status.setRollbackOnly();
        });

        verify(store, never()).schedule(anyLong(), any());
    }

    @Test
    void Redis_등록_실패가_커밋_결과를_실패로_보이게_하지_않고_다음_ID를_시도한다() {
        doThrow(new DataAccessResourceFailureException("Redis unavailable"))
                .when(store).schedule(10L, scheduledAt);

        assertThatCode(() -> transactions().executeWithoutResult(status ->
                store.scheduleAfterCommit(List.of(10L, 20L), scheduledAt)))
                .doesNotThrowAnyException();

        verify(store).schedule(10L, scheduledAt);
        verify(store).schedule(20L, scheduledAt);
    }

    @Test
    void 트랜잭션이_없으면_즉시_등록한다() {
        store.scheduleAfterCommit(List.of(10L), scheduledAt);

        verify(store).schedule(10L, scheduledAt);
    }

    private TransactionTemplate transactions() {
        return new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        });
    }
}
