package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.service.response.TripMapCacheData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class TripMapCacheStoreTest {
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ObjectMapper mapper;
    private TripMapCacheStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        mapper = new ObjectMapper();
        store = new TripMapCacheStore(redis, mapper);
    }

    @Test
    void savesJsonWithMemberKeyAndSixtySecondExpiry() {
        var data = sample();
        store.save(1L, data);
        verify(values).set("cache:trip-map:user:1", mapper.writeValueAsString(data), Duration.ofSeconds(60));
    }

    @Test
    void readsOnlyRequestedMemberAndRestoresCoordinates() {
        var data = sample();
        when(values.get("cache:trip-map:user:1")).thenReturn(mapper.writeValueAsString(data));
        assertThat(store.find(1L)).contains(data);
        assertThat(store.find(2L)).isEmpty();
    }

    @Test
    void redisReadFailureReturnsCacheMiss() {
        when(values.get("cache:trip-map:user:1"))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThat(store.find(1L)).isEmpty();
    }

    @Test
    void brokenJsonReturnsCacheMiss() {
        when(values.get("cache:trip-map:user:1")).thenReturn("broken-json");
        assertThat(store.find(1L)).isEmpty();
    }

    @Test
    void redisWriteFailureDoesNotPropagate() {
        var data = sample();
        doThrow(new DataAccessResourceFailureException("unavailable"))
                .when(values).set("cache:trip-map:user:1", mapper.writeValueAsString(data), Duration.ofSeconds(60));
        assertThatCode(() -> store.save(1L, data)).doesNotThrowAnyException();
    }

    @Test
    void evictsOnlyRequestedMember() {
        store.evict(1L);
        verify(redis).delete("cache:trip-map:user:1");
    }

    @Test
    void deletesAfterCommitButNotWhileTransactionIsRunning() {
        transactions().executeWithoutResult(status -> {
            store.evictAfterCommit(1L);
            verify(redis, never()).delete(anyString());
        });
        verify(redis).delete("cache:trip-map:user:1");
    }

    @Test
    void rollbackKeepsExistingCache() {
        transactions().executeWithoutResult(status -> {
            store.evictAfterCommit(1L);
            status.setRollbackOnly();
        });
        verify(redis, never()).delete(anyString());
    }

    @Test
    void withoutTransactionDeletesImmediately() {
        store.evictAfterCommit(1L);
        verify(redis).delete("cache:trip-map:user:1");
    }

    @Test
    void failedDeletionDoesNotThrowAfterCommit() {
        when(redis.delete("cache:trip-map:user:1"))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThatCode(() -> transactions().executeWithoutResult(status -> store.evictAfterCommit(1L)))
                .doesNotThrowAnyException();
        verify(redis).delete("cache:trip-map:user:1");
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

    private TripMapCacheData sample() {
        return new TripMapCacheData(List.of(new TripMapCacheData.Marker(
                7L, "trip", new BigDecimal("37.5"), new BigDecimal("127.2"), "preview-key", 3L)));
    }
}
