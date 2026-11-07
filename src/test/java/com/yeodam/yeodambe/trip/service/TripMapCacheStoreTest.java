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
    void 회원_키와_60초_만료로_JSON을_저장한다() {
        var data = sample();
        store.save(1L, "v1", data);
        verify(values).set("cache:trip-map:count-v2:user:1:v1", mapper.writeValueAsString(data), Duration.ofSeconds(60));
    }

    @Test
    void 요청한_회원만_조회하고_좌표를_복원한다() {
        var data = sample();
        when(values.get("cache:trip-map:count-v2:user:1:v1")).thenReturn(mapper.writeValueAsString(data));
        assertThat(store.find(1L, "v1")).contains(data);
        assertThat(store.find(2L, "v1")).isEmpty();
    }

    @Test
    void Redis_읽기_실패는_캐시_미스로_처리한다() {
        when(values.get("cache:trip-map:count-v2:user:1:v1"))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThat(store.find(1L, "v1")).isEmpty();
    }

    @Test
    void 손상된_JSON은_캐시_미스로_처리한다() {
        when(values.get("cache:trip-map:count-v2:user:1:v1")).thenReturn("broken-json");
        assertThat(store.find(1L, "v1")).isEmpty();
    }

    @Test
    void Redis_쓰기_실패를_전파하지_않는다() {
        var data = sample();
        doThrow(new DataAccessResourceFailureException("unavailable"))
                .when(values).set("cache:trip-map:count-v2:user:1:v1", mapper.writeValueAsString(data), Duration.ofSeconds(60));
        assertThatCode(() -> store.save(1L, "v1", data)).doesNotThrowAnyException();
    }

    @Test
    void 요청한_회원의_버전만_교체한다() {
        store.evict(1L);
        verify(values).set(eq("cache:trip-map:version:user:1"), anyString(), eq(Duration.ofMinutes(2)));
    }

    @Test
    void 트랜잭션_중에는_유지하고_커밋_후_버전을_교체한다() {
        transactions().executeWithoutResult(status -> {
            store.evictAfterCommit(1L);
            verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        });
        verify(values).set(eq("cache:trip-map:version:user:1"), anyString(), eq(Duration.ofMinutes(2)));
    }

    @Test
    void 롤백하면_기존_캐시를_유지한다() {
        transactions().executeWithoutResult(status -> {
            store.evictAfterCommit(1L);
            status.setRollbackOnly();
        });
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void 트랜잭션이_없으면_즉시_버전을_교체한다() {
        store.evictAfterCommit(1L);
        verify(values).set(eq("cache:trip-map:version:user:1"), anyString(), eq(Duration.ofMinutes(2)));
    }

    @Test
    void 커밋_후_버전_교체_실패는_예외를_던지지_않는다() {
        doThrow(new DataAccessResourceFailureException("unavailable"))
                .when(values).set(eq("cache:trip-map:version:user:1"), anyString(), eq(Duration.ofMinutes(2)));
        assertThatCode(() -> transactions().executeWithoutResult(status -> store.evictAfterCommit(1L)))
                .doesNotThrowAnyException();
        verify(values).set(eq("cache:trip-map:version:user:1"), anyString(), eq(Duration.ofMinutes(2)));
    }

    @Test
    void 기존_버전은_쓰기_없이_재사용한다() {
        when(values.get("cache:trip-map:version:user:1")).thenReturn("v1");
        assertThat(store.version(1L)).contains("v1");
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void 버전이_없으면_2분_만료로_생성한다() {
        when(values.setIfAbsent(eq("cache:trip-map:version:user:1"), anyString(), eq(Duration.ofMinutes(2))))
                .thenReturn(true);
        var result = store.version(1L);
        assertThat(result).isPresent();
        verify(values).setIfAbsent("cache:trip-map:version:user:1", result.orElseThrow(), Duration.ofMinutes(2));
    }

    @Test
    void 동시_생성시_먼저_생성된_버전을_사용한다() {
        when(values.get("cache:trip-map:version:user:1")).thenReturn(null, "winner");
        when(values.setIfAbsent(eq("cache:trip-map:version:user:1"), anyString(), eq(Duration.ofMinutes(2))))
                .thenReturn(false);
        assertThat(store.version(1L)).contains("winner");
    }

    @Test
    void 버전_읽기_실패는_빈_결과를_반환한다() {
        when(values.get("cache:trip-map:version:user:1"))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThat(store.version(1L)).isEmpty();
    }

    @Test
    void 생성_경합에서_진_후_버전이_없으면_빈_결과를_반환한다() {
        assertThat(store.version(1L)).isEmpty();
    }

    @Test
    void 늦은_이전_버전_쓰기는_새_버전_키를_채우지_않는다() {
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
