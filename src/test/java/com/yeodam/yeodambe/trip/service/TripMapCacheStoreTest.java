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
        store.save(1L, "v1", data);
        verify(values).set("cache:trip-map:user:1:v1", mapper.writeValueAsString(data), Duration.ofSeconds(60));
    }

    @Test
    void readsOnlyRequestedMemberAndRestoresCoordinates() {
        var data = sample();
        when(values.get("cache:trip-map:user:1:v1")).thenReturn(mapper.writeValueAsString(data));
        assertThat(store.find(1L, "v1")).contains(data);
        assertThat(store.find(2L, "v1")).isEmpty();
    }

    @Test
    void redisReadFailureReturnsCacheMiss() {
        when(values.get("cache:trip-map:user:1:v1"))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThat(store.find(1L, "v1")).isEmpty();
    }

    @Test
    void brokenJsonReturnsCacheMiss() {
        when(values.get("cache:trip-map:user:1:v1")).thenReturn("broken-json");
        assertThat(store.find(1L, "v1")).isEmpty();
    }

    @Test
    void redisWriteFailureDoesNotPropagate() {
        var data = sample();
        doThrow(new DataAccessResourceFailureException("unavailable"))
                .when(values).set("cache:trip-map:user:1:v1", mapper.writeValueAsString(data), Duration.ofSeconds(60));
        assertThatCode(() -> store.save(1L, "v1", data)).doesNotThrowAnyException();
    }

    @Test
    void rotatesOnlyRequestedMemberVersion() {
        store.evict(1L);
        verify(values).set(eq("cache:trip-map:version:user:1"), anyString(), eq(Duration.ofMinutes(2)));
    }

    @Test
    void rotatesVersionAfterCommitButNotWhileTransactionIsRunning() {
        transactions().executeWithoutResult(status -> {
            store.evictAfterCommit(1L);
            verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        });
        verify(values).set(eq("cache:trip-map:version:user:1"), anyString(), eq(Duration.ofMinutes(2)));
    }

    @Test
    void rollbackKeepsExistingCache() {
        transactions().executeWithoutResult(status -> {
            store.evictAfterCommit(1L);
            status.setRollbackOnly();
        });
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void withoutTransactionRotatesImmediately() {
        store.evictAfterCommit(1L);
        verify(values).set(eq("cache:trip-map:version:user:1"), anyString(), eq(Duration.ofMinutes(2)));
    }

    @Test
    void failedRotationDoesNotThrowAfterCommit() {
        doThrow(new DataAccessResourceFailureException("unavailable"))
                .when(values).set(eq("cache:trip-map:version:user:1"), anyString(), eq(Duration.ofMinutes(2)));
        assertThatCode(() -> transactions().executeWithoutResult(status -> store.evictAfterCommit(1L)))
                .doesNotThrowAnyException();
        verify(values).set(eq("cache:trip-map:version:user:1"), anyString(), eq(Duration.ofMinutes(2)));
    }

    @Test
    void existingVersionIsReusedWithoutWriting() {
        when(values.get("cache:trip-map:version:user:1")).thenReturn("v1");
        assertThat(store.version(1L)).contains("v1");
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void missingVersionIsCreatedWithTwoMinuteExpiry() {
        when(values.setIfAbsent(eq("cache:trip-map:version:user:1"), anyString(), eq(Duration.ofMinutes(2))))
                .thenReturn(true);
        var result = store.version(1L);
        assertThat(result).isPresent();
        verify(values).setIfAbsent("cache:trip-map:version:user:1", result.orElseThrow(), Duration.ofMinutes(2));
    }

    @Test
    void concurrentCreatorUsesWinnerVersion() {
        when(values.get("cache:trip-map:version:user:1")).thenReturn(null, "winner");
        when(values.setIfAbsent(eq("cache:trip-map:version:user:1"), anyString(), eq(Duration.ofMinutes(2))))
                .thenReturn(false);
        assertThat(store.version(1L)).contains("winner");
    }

    @Test
    void versionReadFailureReturnsEmpty() {
        when(values.get("cache:trip-map:version:user:1"))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThat(store.version(1L)).isEmpty();
    }

    @Test
    void missingVersionAfterLostCreationReturnsEmpty() {
        assertThat(store.version(1L)).isEmpty();
    }

    @Test
    void lateOldVersionWriteDoesNotPopulateNewVersionKey() {
        var entries = new java.util.HashMap<String, String>();
        entries.put("cache:trip-map:version:user:1", "v1");
        when(values.get(anyString())).thenAnswer(call -> entries.get(call.getArgument(0)));
        doAnswer(call -> {
            entries.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));

        String oldVersion = store.version(1L).orElseThrow();
        store.evict(1L);
        String newVersion = store.version(1L).orElseThrow();
        store.save(1L, oldVersion, sample());

        assertThat(newVersion).isNotEqualTo(oldVersion);
        assertThat(store.find(1L, newVersion)).isEmpty();
        assertThat(store.find(1L, oldVersion)).contains(sample());
        store.save(1L, newVersion, new TripMapCacheData(List.of()));
        assertThat(store.find(1L, newVersion)).contains(new TripMapCacheData(List.of()));
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
