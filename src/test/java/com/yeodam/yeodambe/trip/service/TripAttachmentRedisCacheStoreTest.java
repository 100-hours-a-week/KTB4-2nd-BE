package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentReadRejectedException;
import com.yeodam.yeodambe.trip.client.TripAttachmentRedisLockClient;
import com.yeodam.yeodambe.trip.config.TripAttachmentCacheProperties;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.fixture.TripAttachmentCacheErrorMessage;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository.PhotoReadState;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository.ReadGrantRow;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.service.request.AttachmentPageKey;
import com.yeodam.yeodambe.trip.service.request.AttachmentPageQuery;
import com.yeodam.yeodambe.trip.service.request.cursor.AttachmentCursor;
import com.yeodam.yeodambe.trip.service.response.AttachmentPageData;
import com.yeodam.yeodambe.trip.service.response.AttachmentPageReadScope;
import com.yeodam.yeodambe.trip.service.response.AttachmentReadRefresh;
import com.yeodam.yeodambe.trip.service.response.CachedAttachmentResponse;
import com.yeodam.yeodambe.trip.service.response.CachedTripReadAccessSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentListResponse;
import com.yeodam.yeodambe.trip.service.response.TripReadAccessSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripReadPermit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers
class TripAttachmentRedisCacheStoreTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate template;
    private static RedissonClient firstRedisson;
    private static RedissonClient secondRedisson;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong clock = new AtomicLong();
    private final AttachmentPageQuery query = new AttachmentPageQuery(1L, 2L, 3L, null);
    private final AttachmentPageKey key = new AttachmentPageKey(2L, 3L, null);
    private TripAttachmentCacheProperties properties;
    private TripAttachmentRedisCacheStore store;
    private TripAttachmentRedisLockClient locks;
    private TripAttachmentRedisLockClient secondLocks;

    @BeforeAll
    static void connect() {
        connection = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connection.afterPropertiesSet();
        connection.start();
        template = new StringRedisTemplate(connection);
        template.afterPropertiesSet();
        firstRedisson = newRedissonClient();
        secondRedisson = newRedissonClient();
    }

    @AfterAll
    static void disconnect() {
        if (firstRedisson != null) {
            firstRedisson.shutdown();
        }
        if (secondRedisson != null) {
            secondRedisson.shutdown();
        }
        if (connection != null) {
            connection.destroy();
        }
    }

    @BeforeEach
    void prepare() {
        properties = new TripAttachmentCacheProperties();
        properties.setAccessSchemaVersion(1);
        properties.setResponseSchemaVersion(1);
        properties.setPageSchemaVersion(1);
        locks = new TripAttachmentRedisLockClient(firstRedisson, properties);
        secondLocks = new TripAttachmentRedisLockClient(secondRedisson, properties);
        properties.setNamespace("final-unit-" + UUID.randomUUID());
        store = new TripAttachmentRedisCacheStore(template, json, properties, clock::get);
    }

    @Test
    void 주입한_권한과_응답의_스키마를_키와_해시와_payload에_함께_사용한다() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "app.photo-list-cache.access-schema-version", "2",
                "app.photo-list-cache.response-schema-version", "3",
                "app.photo-list-cache.page-schema-version", "7")));
        binder.bind("app.photo-list-cache", Bindable.ofInstance(properties));

        AttachmentReadRefresh accessRefresh = beginAccess(store, locks);
        String accessBase = properties.getNamespace()
                + ":access:schema:2:{48e0d1fa9385dc6a9d4bdd8f871c84ee3836c335a763932147ef153cf0c171ab}";
        try {
            assertThat(accessRefresh.valueKey()).isEqualTo(accessBase + ":value");
            assertThat(accessRefresh.lockKey()).isEqualTo(accessBase + ":lock");
            assertThat(store.publishAccess(2L, accessRefresh, accessSnapshot()))
                    .isEqualTo(TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED);
            assertThat(json.readTree(template.opsForValue().get(accessRefresh.valueKey()))
                    .path("schemaVersion").asInt()).isEqualTo(2);
            assertThat(store.findAccess(2L).orElseThrow().snapshot()).isEqualTo(accessSnapshot());
        } finally {
            locks.release(2L, accessRefresh.lockKey(), accessRefresh.ownerLock());
        }

        AttachmentReadRefresh responseRefresh = begin(store, locks);
        String responseBase = properties.getNamespace()
                + ":final-response:schema:3:{ff6e2b20aac7c40520a039f9d55536daa6e551254ded3896ce324f8c32bdf0d4}";
        try {
            assertThat(responseRefresh.valueKey()).isEqualTo(responseBase + ":value");
            assertThat(responseRefresh.lockKey()).isEqualTo(responseBase + ":lock");
            assertThat(store.publishResponse(key, responseRefresh, 4L, response(10L)))
                    .isEqualTo(TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED);
            assertThat(json.readTree(template.opsForValue().get(responseRefresh.valueKey()))
                    .path("schemaVersion").asInt()).isEqualTo(3);
            assertThat(store.findResponse(key).orElseThrow().response()).isEqualTo(response(10L));
        } finally {
            locks.release(2L, responseRefresh.lockKey(), responseRefresh.ownerLock());
        }
    }

    @Test
    void 권한과_응답은_같은_키에_저장된_다른_스키마의_payload를_사용하지_않는다() {
        AttachmentReadRefresh accessRefresh = beginAccess(store, locks);
        try {
            assertThat(store.publishAccess(2L, accessRefresh, accessSnapshot()))
                    .isEqualTo(TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED);
            ObjectNode payload = (ObjectNode) json.readTree(template.opsForValue().get(accessRefresh.valueKey()));
            payload.put("schemaVersion", 99);
            template.opsForValue().set(accessRefresh.valueKey(), json.writeValueAsString(payload), Duration.ofSeconds(1));

            assertThat(store.findAccess(2L)).isEmpty();
        } finally {
            locks.release(2L, accessRefresh.lockKey(), accessRefresh.ownerLock());
        }

        AttachmentReadRefresh responseRefresh = begin(store, locks);
        try {
            assertThat(store.publishResponse(key, responseRefresh, 4L, response(10L)))
                    .isEqualTo(TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED);
            ObjectNode payload = (ObjectNode) json.readTree(template.opsForValue().get(responseRefresh.valueKey()));
            payload.put("schemaVersion", 99);
            template.opsForValue().set(responseRefresh.valueKey(), json.writeValueAsString(payload), Duration.ofSeconds(1));

            assertThat(store.findResponse(key)).isEmpty();
        } finally {
            locks.release(2L, responseRefresh.lockKey(), responseRefresh.ownerLock());
        }
    }

    @Test
    void 서로_다른_스키마의_서버는_권한과_응답과_락을_분리한다() {
        TripAttachmentCacheProperties otherProperties = new TripAttachmentCacheProperties();
        otherProperties.setNamespace(properties.getNamespace());
        otherProperties.setAccessSchemaVersion(2);
        otherProperties.setResponseSchemaVersion(2);
        otherProperties.setPageSchemaVersion(2);
        TripAttachmentRedisCacheStore otherStore = new TripAttachmentRedisCacheStore(
                template, json, otherProperties, clock::get);

        AttachmentReadRefresh accessRefresh = beginAccess(store, locks);
        try {
            assertThat(store.publishAccess(2L, accessRefresh, accessSnapshot()))
                    .isEqualTo(TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED);
            assertThat(otherStore.findAccess(2L)).isEmpty();
            assertThat(otherStore.accessLockKey(2L)).isNotEqualTo(accessRefresh.lockKey());
        } finally {
            locks.release(2L, accessRefresh.lockKey(), accessRefresh.ownerLock());
        }

        AttachmentReadRefresh responseRefresh = begin(store, locks);
        try {
            assertThat(store.publishResponse(key, responseRefresh, 4L, response(10L)))
                    .isEqualTo(TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED);
            assertThat(otherStore.findResponse(key)).isEmpty();
            assertThat(otherStore.responseLockKey(key)).isNotEqualTo(responseRefresh.lockKey());
        } finally {
            locks.release(2L, responseRefresh.lockKey(), responseRefresh.ownerLock());
        }
    }

    @Test
    void 시작_후_300밀리초에_저장하면_유효기간은_최대_200밀리초만_남는다() {
        AttachmentReadRefresh refresh = begin(store, locks);
        clock.set(300_000_000L);
        assertThat(store.publishResponse(key, refresh, 4L, response(10L))).isEqualTo(
                TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED);
        CachedAttachmentResponse cached = store.findResponse(key).orElseThrow();
        assertThat(cached.response()).isEqualTo(response(10L));
        assertThat(cached.validUntilNanos() - clock.get()).isPositive().isLessThanOrEqualTo(200_000_000L);
    }

    @Test
    void 다른_페이지의_캐시_응답은_조회할_수_없다() {
        store.publishResponse(key, begin(store, locks), 4L, response(10L));
        AttachmentPageKey otherPage = new AttachmentPageKey(2L, 4L, null);
        assertThat(store.findResponse(otherPage)).isEmpty();
    }

    @Test
    void 응답_시퀀스는_스냅샷_버전과_일치해야_한다() {
        AttachmentReadRefresh refresh = begin(store, locks);

        assertThatThrownBy(() -> store.publishResponse(key, refresh, 5L, response(10L)))
                .isInstanceOf(AttachmentReadRejectedException.class);
        assertThat(store.findResponse(key)).isEmpty();
    }

    @Test
    void 응답_커서는_다른_폴더를_참조할_수_없다() {
        String cursor = new AttachmentCursor(LocalDateTime.of(2026, 11, 4, 12, 0),
                10L, query.tripId(), query.tripPlaceId() + 1L, null).encode(json);
        TripAttachmentListResponse response = new TripAttachmentListResponse(
                List.of(new TripAttachmentListResponse.Item(10L, "https://example.test/preview")),
                true, cursor, 4L);
        AttachmentReadRefresh refresh = begin(store, locks);

        assertThatThrownBy(() -> store.publishResponse(key, refresh, 4L, response))
                .isInstanceOf(AttachmentReadRejectedException.class);
        assertThat(store.findResponse(key)).isEmpty();
    }

    @Test
    void 게시한_응답은_Redisson_잠금_해제_전에도_조회된다() {
        AttachmentReadRefresh refresh = begin(store, locks);
        TripAttachmentRedisCacheStore contender = secondStore();
        assertThat(store.publishResponse(key, refresh, 4L, response(10L))).isEqualTo(
                TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED);
        assertThat(store.findResponse(key)).isPresent();
        assertThat(secondLocks.tryAcquire(key.tripId(), contender.responseLockKey(key), 0L)).isEmpty();
        assertThat(locks.release(key.tripId(), store.responseLockKey(key), refresh.ownerLock())).isTrue();
        RLock successor = secondLocks.tryAcquire(key.tripId(), contender.responseLockKey(key), 0L).orElseThrow();
        assertThat(locks.release(key.tripId(), store.responseLockKey(key), refresh.ownerLock())).isFalse();
        assertThat(secondLocks.release(key.tripId(), contender.responseLockKey(key), successor)).isTrue();
    }

    @Test
    void 서로_다른_서버와_사용자가_권한과_응답_갱신을_공유한다() throws Exception {
        TripAttachmentPageReader reader = mock(TripAttachmentPageReader.class);
        when(reader.normalizeKey(any())).thenReturn(key);
        when(reader.readShared(key)).thenReturn(new AttachmentPageData(List.of(), false, null, 4L));
        TripRepository trips = mock(TripRepository.class);
        TripShareUserRepository shares = mock(TripShareUserRepository.class);
        PhotoReadState state = mock(PhotoReadState.class);
        when(state.getTripId()).thenReturn(2L);
        when(state.getOwnerId()).thenReturn(1L);
        CountDownLatch accessLoading = new CountDownLatch(1);
        CountDownLatch releaseAccess = new CountDownLatch(1);
        CountDownLatch competingAccess = new CountDownLatch(1);
        AtomicLong representativeThread = new AtomicLong();
        when(trips.findPhotoReadStateById(2L)).thenAnswer(invocation -> {
            representativeThread.set(Thread.currentThread().threadId());
            accessLoading.countDown();
            await(releaseAccess);
            return Optional.of(state);
        });
        ReadGrantRow readGrant = readGrant(9L, TripSharePermission.READ);
        ReadGrantRow writeGrant = readGrant(10L, TripSharePermission.WRITE);
        when(shares.findReadableGrantsByTripIdAndOwnerId(any(), any(), any())).thenReturn(List.of(readGrant, writeGrant));
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        Clock wall = Clock.fixed(Instant.parse("2026-11-05T03:00:00Z"), ZoneOffset.UTC);
        TripAttachmentReadAccessService firstAccess = new TripAttachmentReadAccessService(
                trips, shares, store, locks, transactions, wall, clock::get);
        CountDownLatch mapping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch competing = new CountDownLatch(1);
        AtomicInteger mappings = new AtomicInteger();
        RedissonClient observed = mock(RedissonClient.class);
        doAnswer(invocation -> {
            String lockName = invocation.getArgument(0);
            RLock actual = secondRedisson.getLock(lockName);
            RLock contending = mock(RLock.class, delegatesTo(actual));
            doAnswer(acquisition -> {
                long wait = acquisition.getArgument(0);
                long lease = acquisition.getArgument(1);
                TimeUnit unit = acquisition.getArgument(2);
                long started = System.nanoTime();
                assertThat(firstRedisson.getLock(lockName).isHeldByThread(representativeThread.get())).isTrue();
                assertThat(actual.tryLock(0L, lease, unit)).isFalse();
                if (lockName.contains(":access:")) {
                    competingAccess.countDown();
                } else {
                    competing.countDown();
                }
                long remaining = Math.max(0L, unit.toNanos(wait) - (System.nanoTime() - started));
                return actual.tryLock(remaining, unit.toNanos(lease), TimeUnit.NANOSECONDS);
            }).when(contending).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
            return contending;
        }).when(observed).getLock(anyString());
        TripAttachmentRedisLockClient remoteLocks = new TripAttachmentRedisLockClient(observed, properties);
        TripAttachmentRedisCacheStore remoteStore = new TripAttachmentRedisCacheStore(
                template, json, properties, clock::get);
        TripAttachmentReadAccessService secondAccess = spy(new TripAttachmentReadAccessService(
                trips, shares, remoteStore, remoteLocks, transactions, wall, clock::get));
        doAnswer(invocation -> {
            TripReadPermit permit = (TripReadPermit) invocation.callRealMethod();
            await(mapping);
            return permit;
        }).when(secondAccess).requireReadable(any(), any(), any());
        TripAttachmentResponseCacheService first = new TripAttachmentResponseCacheService(
                reader, store, locks, firstAccess, event -> {
                }, clock::get);
        TripAttachmentResponseCacheService second = new TripAttachmentResponseCacheService(
                reader, remoteStore, remoteLocks, secondAccess, event -> {
                }, clock::get);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TripAttachmentListResponse> leader = executor.submit(() -> first.read(query, data -> {
                mappings.incrementAndGet();
                mapping.countDown();
                await(release);
                return response(10L);
            }));
            try {
                await(accessLoading);
                Future<TripAttachmentListResponse> follower = executor.submit(() -> second.read(new AttachmentPageQuery(9L, 2L, 3L, null), data -> {
                    mappings.incrementAndGet();
                    mapping.countDown();
                    await(release);
                    return response(10L);
                }));
                await(competingAccess);
                assertThat(follower.isDone()).isFalse();
                releaseAccess.countDown();
                await(mapping);
                Future<TripAttachmentListResponse> writer = executor.submit(() -> second.read(
                        new AttachmentPageQuery(10L, 2L, 3L, null), data -> {
                            mappings.incrementAndGet();
                            return response(10L);
                        }));
                await(competing);
                assertThat(follower.isDone()).isFalse();
                release.countDown();
                assertThat(leader.get(3, TimeUnit.SECONDS)).isEqualTo(response(10L));
                assertThat(follower.get(3, TimeUnit.SECONDS)).isEqualTo(response(10L));
                assertThat(writer.get(3, TimeUnit.SECONDS)).isEqualTo(response(10L));
            } finally {
                releaseAccess.countDown();
                release.countDown();
            }
        }
        assertThat(mappings).hasValue(1);
        verify(reader).readShared(key);
        verify(trips).findPhotoReadStateById(2L);
        verify(shares).findReadableGrantsByTripIdAndOwnerId(any(), any(), any());
    }

    @Test
    void Redisson_잠금_대기_시간은_제한된다() {
        RLock owner = locks.tryAcquire(key.tripId(), store.responseLockKey(key), 0L).orElseThrow();
        try {
            assertThat(secondLocks.tryAcquire(
                    key.tripId(), secondStore().responseLockKey(key), TimeUnit.MILLISECONDS.toNanos(50))).isEmpty();
        } finally {
            locks.release(key.tripId(), store.responseLockKey(key), owner);
        }
    }

    @Test
    void Redisson_대기_인터럽트가_인터럽트_상태와_소유자를_보존한다() throws Exception {
        RLock owner = locks.tryAcquire(key.tripId(), store.responseLockKey(key), 0L).orElseThrow();
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<AttachmentReadRejectedException.Reason> reason = new AtomicReference<>();
        Thread contender = Thread.ofVirtual().start(() -> {
            waiting.countDown();
            try {
                secondLocks.tryAcquire(key.tripId(), secondStore().responseLockKey(key), TimeUnit.SECONDS.toNanos(2));
            } catch (AttachmentReadRejectedException failure) {
                reason.set(failure.reason());
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            await(waiting);
            contender.interrupt();
            assertThat(contender.join(Duration.ofSeconds(3))).isTrue();
            assertThat(reason.get()).isEqualTo(AttachmentReadRejectedException.Reason.INTERRUPTED);
            assertThat(interrupted).isTrue();
            assertThat(owner.isHeldByCurrentThread()).isTrue();
        } finally {
            locks.release(key.tripId(), store.responseLockKey(key), owner);
        }
    }

    @Test
    void 이전_소유자는_새_잠금을_삭제하거나_응답을_게시할_수_없다() {
        AttachmentReadRefresh first = begin(store, locks);
        assertThat(locks.release(key.tripId(), store.responseLockKey(key), first.ownerLock())).isTrue();
        TripAttachmentRedisCacheStore successorStore = secondStore();
        AttachmentReadRefresh second = begin(successorStore, secondLocks);
        assertThat(store.publishResponse(key, first, 4L, response(10L))).isEqualTo(
                TripAttachmentRedisCacheStore.PublishOutcome.OWNER_LOST);
        assertThat(locks.release(key.tripId(), store.responseLockKey(key), first.ownerLock())).isFalse();
        assertThat(locks.tryAcquire(key.tripId(), store.responseLockKey(key), 0L)).isEmpty();
        assertThat(successorStore.publishResponse(key, second, 4L, response(20L))).isEqualTo(
                TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED);
        assertThat(store.findResponse(key).orElseThrow().response()).isEqualTo(response(20L));
    }

    @Test
    void 게시_커밋_전_소유자가_바뀌면_이전_응답을_거부한다() {
        StringRedisTemplate observed = spy(template);
        TripAttachmentRedisCacheStore client = new TripAttachmentRedisCacheStore(
                observed, json, properties, clock::get);
        AttachmentReadRefresh refresh = begin(client, locks);
        String lockKey = template.keys(properties.getNamespace() + "*:lock").iterator().next();
        replaceOwnerBeforeFirstCommit(observed, lockKey);

        assertThat(client.publishResponse(key, refresh, 4L, response(10L)))
                .isEqualTo(TripAttachmentRedisCacheStore.PublishOutcome.OWNER_LOST);
        assertThat(secondRedisson.getLock(lockKey).isHeldByCurrentThread()).isTrue();
        assertThat(client.findResponse(key)).isEmpty();
        assertThat(locks.release(key.tripId(), client.responseLockKey(key), refresh.ownerLock())).isFalse();
    }

    @Test
    void 다른_스레드의_해제는_소유자의_잠금을_삭제하지_못한다() throws Exception {
        RLock owner = locks.tryAcquire(key.tripId(), store.responseLockKey(key), 0L).orElseThrow();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> released = executor.submit(() -> locks.release(key.tripId(), store.responseLockKey(key), owner));
            assertThat(released.get(3, TimeUnit.SECONDS)).isFalse();
        }
        assertThat(owner.isHeldByCurrentThread()).isTrue();
        assertThat(locks.release(key.tripId(), store.responseLockKey(key), owner)).isTrue();
    }

    @Test
    void 커밋_지연이_저장된_응답의_마감을_늘리지_않는다() {
        StringRedisTemplate observed = spy(template);
        TripAttachmentRedisCacheStore client = new TripAttachmentRedisCacheStore(
                observed, json, properties, clock::get);
        AttachmentReadRefresh refresh = begin(client, locks);
        doAnswer(invocation -> {
            Thread.sleep(550L);
            clock.set(550_000_000L);
            return invocation.callRealMethod();
        }).when(observed).exec();

        client.publishResponse(key, refresh, 4L, response(10L));

        assertThat(client.findResponse(key)).isEmpty();
        assertThat(template.keys(properties.getNamespace() + "*:value")).isEmpty();
    }

    @Test
    void 명령_큐_오류시_트랜잭션을_폐기하고_원래_실패를_유지한다() {
        StringRedisTemplate observed = spy(template);
        TripAttachmentRedisCacheStore client = new TripAttachmentRedisCacheStore(
                observed, json, properties, clock::get);
        AttachmentReadRefresh refresh = begin(client, locks);
        AssertionError failure = new AssertionError(TripAttachmentCacheErrorMessage.COMMAND_QUEUE_FAILED.message());
        doAnswer(invocation -> {
            throw failure;
        }).when(observed).expireAt(anyString(), any(Instant.class));

        assertThatThrownBy(() -> client.publishResponse(key, refresh, 4L, response(10L))).isSameAs(failure);

        verify(observed).discard();
        assertThat(template.keys(properties.getNamespace() + "*:value")).isEmpty();
        assertThat(locks.release(key.tripId(), client.responseLockKey(key), refresh.ownerLock())).isTrue();
    }

    @Test
    void 임대_만료시_새_소유자를_허용하고_늦은_이전_소유자를_거부한다() throws InterruptedException {
        AttachmentReadRefresh first = begin(store, locks);
        String lockKey = template.keys(properties.getNamespace() + "*:lock").iterator().next();
        template.expire(lockKey, Duration.ofMillis(1));
        Thread.sleep(80L);
        TripAttachmentRedisCacheStore secondClient = secondStore();
        AttachmentReadRefresh second = begin(secondClient, secondLocks);
        assertThat(store.publishResponse(key, first, 4L, response(10L))).isEqualTo(
                TripAttachmentRedisCacheStore.PublishOutcome.OWNER_LOST);
        assertThat(locks.release(key.tripId(), store.responseLockKey(key), first.ownerLock())).isFalse();
        assertThat(secondClient.publishResponse(key, second, 4L, response(20L))).isEqualTo(
                TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED);
    }

    @Test
    void 중복_완료가_TTL을_늘리지_않는다() throws InterruptedException {
        AttachmentReadRefresh refresh = begin(store, locks);
        store.publishResponse(key, refresh, 4L, response(10L));
        Long firstDeadline = template.execute(RedisScript.of("return redis.call('PEXPIRETIME', KEYS[1])", Long.class),
                List.of(refresh.valueKey()));
        assertThat(firstDeadline).isPositive();
        Thread.sleep(30L);
        assertThat(store.publishResponse(key, refresh, 4L, response(10L))).isEqualTo(
                TripAttachmentRedisCacheStore.PublishOutcome.ALREADY_PUBLISHED);
        Long remainingDeadline = template.execute(RedisScript.of("return redis.call('PEXPIRETIME', KEYS[1])", Long.class),
                List.of(refresh.valueKey()));
        // 재조회 전에 정상 만료될 수 있으므로 서버의 절대 만료 시각 또는 키 만료를 확인한다.
        assertThat(remainingDeadline).isIn(firstDeadline, -2L);
    }

    @Test
    void 이전_페이로드와_새_TTL을_섞어_조회하지_않는다() throws Exception {
        CountDownLatch published = new CountDownLatch(1);
        TripAttachmentRedisCacheStore receiver = new TripAttachmentRedisCacheStore(
                template, json, properties, () -> 0L);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> writer = executor.submit(() -> {
                for (long generation = 1; generation <= 40; generation++) {
                    clock.set(0L);
                    AttachmentReadRefresh refresh = begin(store, locks);
                    clock.set(generation % 2 == 0 ? 300_000_000L : 0L);
                    assertThat(store.publishResponse(key, refresh, 4L, response(generation))).isEqualTo(
                            TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED);
                    locks.release(key.tripId(), store.responseLockKey(key), refresh.ownerLock());
                    published.countDown();
                }
            });
            assertThat(published.await(3, TimeUnit.SECONDS)).isTrue();
            int validatedSnapshots = 0;
            for (int sample = 0; sample < 100; sample++) {
                Optional<CachedAttachmentResponse> snapshot = receiver.findResponse(key);
                if (snapshot.isPresent()) {
                    CachedAttachmentResponse cached = snapshot.get();
                    long maximum = cached.response().items().getFirst().tripAttachmentId() % 2 == 0
                            ? 200_000_000L : 495_000_000L;
                    assertThat(cached.validUntilNanos()).isPositive().isLessThanOrEqualTo(maximum);
                    assertThat(cached.response()).isEqualTo(response(cached.response().items().getFirst().tripAttachmentId()));
                    validatedSnapshots++;
                }
            }
            writer.get(3, TimeUnit.SECONDS);
            assertThat(validatedSnapshots).isPositive();
        }
    }

    @Test
    void 만료된_세대의_응답은_게시하지_않는다() {
        AttachmentReadRefresh refresh = begin(store, locks);
        clock.set(500_000_000L);
        assertThat(store.publishResponse(key, refresh, 4L, response(10L))).isEqualTo(
                TripAttachmentRedisCacheStore.PublishOutcome.EXPIRED);
        assertThat(store.findResponse(key)).isEmpty();
        locks.release(key.tripId(), store.responseLockKey(key), refresh.ownerLock());
    }

    @Test
    void 권한을_300밀리초에_저장하면_유효기간은_최대_200밀리초만_남는다() {
        AttachmentReadRefresh refresh = beginAccess(store, locks);
        clock.set(300_000_000L);
        assertThat(store.publishAccess(2L, refresh, accessSnapshot())).isEqualTo(
                TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED);
        CachedTripReadAccessSnapshot cached = store.findAccess(2L).orElseThrow();
        assertThat(cached.snapshot()).isEqualTo(accessSnapshot());
        assertThat(cached.validUntilNanos() - clock.get()).isPositive().isLessThanOrEqualTo(200_000_000L);
        assertThat(store.findAccess(8L)).isEmpty();
    }

    @Test
    void 권한_페이로드의_다른_여행과_중복_회원을_거부한다() {
        AttachmentReadRefresh refresh = beginAccess(store, locks);
        assertThatThrownBy(() -> store.publishAccess(9L, refresh, accessSnapshot()))
                .isInstanceOf(AttachmentReadRejectedException.class);
        TripReadAccessSnapshot duplicate = new TripReadAccessSnapshot(2L, 1L,
                List.of(accessSnapshot().members().getFirst(), accessSnapshot().members().getFirst()));
        assertThatThrownBy(() -> store.publishAccess(2L, refresh, duplicate))
                .isInstanceOf(AttachmentReadRejectedException.class);
        TripReadAccessSnapshot invalid = new TripReadAccessSnapshot(2L, 1L, List.of(
                new TripReadAccessSnapshot.Member(3L, null, Instant.parse("2026-11-06T00:00:00Z"))));
        assertThatThrownBy(() -> store.publishAccess(2L, refresh, invalid))
                .isInstanceOf(AttachmentReadRejectedException.class);
        assertThat(store.findAccess(2L)).isEmpty();
    }

    @Test
    void 이전_권한_소유자는_게시하거나_후임_잠금을_해제할_수_없다() {
        AttachmentReadRefresh old = beginAccess(store, locks);
        locks.release(2L, store.accessLockKey(2L), old.ownerLock());
        TripAttachmentRedisCacheStore successor = secondStore();
        AttachmentReadRefresh current = beginAccess(successor, secondLocks);
        assertThat(store.publishAccess(2L, old, accessSnapshot())).isEqualTo(
                TripAttachmentRedisCacheStore.PublishOutcome.OWNER_LOST);
        assertThat(locks.release(2L, store.accessLockKey(2L), old.ownerLock())).isFalse();
        assertThat(current.ownerLock().isHeldByCurrentThread()).isTrue();
        assertThat(successor.publishAccess(2L, current, accessSnapshot())).isEqualTo(
                TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED);
    }

    @Test
    void 권한_EXEC_지연이_유효기간을_갱신하지_않는다() {
        StringRedisTemplate observed = spy(template);
        TripAttachmentRedisCacheStore client = new TripAttachmentRedisCacheStore(
                observed, json, properties, clock::get);
        AttachmentReadRefresh refresh = beginAccess(client, locks);
        doAnswer(invocation -> {
            Thread.sleep(550L);
            clock.set(550_000_000L);
            return invocation.callRealMethod();
        }).when(observed).exec();
        client.publishAccess(2L, refresh, accessSnapshot());
        assertThat(client.findAccess(2L)).isEmpty();
    }

    @Test
    void 권한_게시_응답을_유실하면_동일_스냅샷만_확인한다() {
        StringRedisTemplate observed = spy(template);
        TripAttachmentRedisCacheStore client = new TripAttachmentRedisCacheStore(
                observed, json, properties, clock::get);
        AttachmentReadRefresh refresh = beginAccess(client, locks);
        AtomicBoolean lost = new AtomicBoolean();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (lost.compareAndSet(false, true)) {
                throw new org.springframework.dao.DataAccessResourceFailureException(
                        TripAttachmentCacheErrorMessage.COMMAND_QUEUE_FAILED.message());
            }
            return result;
        }).when(observed).exec();
        assertThat(client.publishAccess(2L, refresh, accessSnapshot())).isEqualTo(
                TripAttachmentRedisCacheStore.PublishOutcome.ALREADY_PUBLISHED);
        long firstDeadline = client.findAccess(2L).orElseThrow().validUntilNanos();
        assertThat(client.publishAccess(2L, refresh, accessSnapshot())).isEqualTo(
                TripAttachmentRedisCacheStore.PublishOutcome.ALREADY_PUBLISHED);
        assertThat(client.findAccess(2L).orElseThrow().validUntilNanos()).isLessThanOrEqualTo(firstDeadline);
    }

    @Test
    void 응답_갱신을_권한_갱신으로_게시할_수_없다() {
        AttachmentReadRefresh refresh = begin(store, locks);
        assertThatThrownBy(() -> store.publishAccess(2L, refresh, accessSnapshot()))
                .isInstanceOf(AttachmentReadRejectedException.class);
        assertThat(store.findAccess(2L)).isEmpty();
    }

    @Test
    void 한_회원의_만료가_전체_권한_스냅샷을_무효화하지_않는다() {
        TripReadAccessSnapshot snapshot = new TripReadAccessSnapshot(2L, 1L, List.of(
                new TripReadAccessSnapshot.Member(3L, TripSharePermission.READ, Instant.parse("2020-01-01T00:00:00Z")),
                new TripReadAccessSnapshot.Member(4L, TripSharePermission.WRITE, Instant.parse("2030-01-01T00:00:00Z"))));
        assertThat(store.publishAccess(2L, beginAccess(store, locks), snapshot)).isEqualTo(
                TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED);
        assertThat(store.findAccess(2L).orElseThrow().snapshot()).isEqualTo(snapshot);
    }

    @Test
    void 이전_v형식의_키를_새_스키마_캐시로_읽지_않는다() {
        template.opsForValue().set(properties.getNamespace() + ":final-response:v3:{816ec232a6c8ae65f7646251351d09496bf2aa360f168b10be96e2f34b83484f}:value",
                "legacy", Duration.ofSeconds(30));
        template.opsForValue().set(properties.getNamespace() + ":page:v3:4:ad958bec16d14a6a40be3bc037be9cbc54fc3cdd17f0318d89480ed00b141720",
                "legacy", Duration.ofSeconds(30));
        template.opsForValue().set(properties.getNamespace()
                        + ":access:v1:{22074227d8462b39403011e0bc4c5e7a3f1ee1bae54ae2deb0943dece537f93f}:value",
                "legacy", Duration.ofSeconds(30));
        template.opsForValue().set(properties.getNamespace()
                        + ":final-response:v4:{b5f6da27fd146689fc4b3df39514956a1c1cf36059570d136eb757c20f908505}:value",
                "legacy", Duration.ofSeconds(30));
        template.opsForValue().set(properties.getNamespace()
                        + ":page:v4:4:b5f6da27fd146689fc4b3df39514956a1c1cf36059570d136eb757c20f908505",
                "legacy", Duration.ofSeconds(30));
        assertThat(store.findAccess(2L)).isEmpty();
        assertThat(store.findResponse(key)).isEmpty();
        TripAttachmentPageStore pages = new TripAttachmentPageStore(template, json, properties);
        assertThat(pages.find(key, 4L)).isEmpty();
        assertThat(template.keys(properties.getNamespace() + "*:v*:*")).hasSize(5);
    }

    private ReadGrantRow readGrant(
            Long userId,
            TripSharePermission permission
    ) {
        ReadGrantRow grant = mock(
                ReadGrantRow.class);
        when(grant.getGranteeUserId()).thenReturn(userId);
        when(grant.getPermission()).thenReturn(permission);
        when(grant.getExpiresAt()).thenReturn(LocalDateTime.of(2026, 11, 6, 12, 0));
        return grant;
    }

    @Test
    void 권한_커밋_전_소유자가_바뀌면_이전_스냅샷을_거부한다() {
        StringRedisTemplate observed = spy(template);
        TripAttachmentRedisCacheStore client = new TripAttachmentRedisCacheStore(
                observed, json, properties, clock::get);
        AttachmentReadRefresh refresh = beginAccess(client, locks);
        replaceOwnerBeforeFirstCommit(observed, refresh.lockKey());
        assertThat(client.publishAccess(2L, refresh, accessSnapshot())).isEqualTo(
                TripAttachmentRedisCacheStore.PublishOutcome.OWNER_LOST);
        assertThat(client.findAccess(2L)).isEmpty();
        assertThat(locks.release(2L, client.accessLockKey(2L), refresh.ownerLock())).isFalse();
        assertThat(secondRedisson.getLock(refresh.lockKey()).isHeldByCurrentThread()).isTrue();
    }

    @Test
    void 권한_임대_만료시_후임을_허용하고_이전_소유자를_거부한다() throws InterruptedException {
        AttachmentReadRefresh old = beginAccess(store, locks);
        template.expire(old.lockKey(), Duration.ofMillis(1));
        Thread.sleep(80L);
        TripAttachmentRedisCacheStore successor = secondStore();
        AttachmentReadRefresh current = beginAccess(successor, secondLocks);
        assertThat(store.publishAccess(2L, old, accessSnapshot())).isEqualTo(
                TripAttachmentRedisCacheStore.PublishOutcome.OWNER_LOST);
        assertThat(locks.release(2L, store.accessLockKey(2L), old.ownerLock())).isFalse();
        assertThat(successor.publishAccess(2L, current, accessSnapshot())).isEqualTo(
                TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED);
    }

    @Test
    void 권한_잠금_대기_인터럽트가_인터럽트_상태와_소유자를_보존한다() throws Exception {
        RLock owner = locks.tryAcquire(2L, store.accessLockKey(2L), 0L).orElseThrow();
        AtomicReference<AttachmentReadRejectedException.Reason> reason = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch waiting = new CountDownLatch(1);
        Thread contender = Thread.ofVirtual().start(() -> {
            waiting.countDown();
            try {
                secondLocks.tryAcquire(2L, secondStore().accessLockKey(2L), TimeUnit.SECONDS.toNanos(2));
            } catch (AttachmentReadRejectedException exception) {
                reason.set(exception.reason());
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            await(waiting);
            contender.interrupt();
            assertThat(contender.join(Duration.ofSeconds(3))).isTrue();
            assertThat(reason.get()).isEqualTo(AttachmentReadRejectedException.Reason.INTERRUPTED);
            assertThat(interrupted).isTrue();
            assertThat(owner.isHeldByCurrentThread()).isTrue();
        } finally {
            locks.release(2L, store.accessLockKey(2L), owner);
        }
    }

    private AttachmentReadRefresh beginAccess(
            TripAttachmentRedisCacheStore client,
            TripAttachmentRedisLockClient locks
    ) {
        RLock lock = locks.tryAcquire(2L, client.accessLockKey(2L), 0L).orElseThrow();
        return client.beginAccessRefresh(2L, lock);
    }

    private TripReadAccessSnapshot accessSnapshot() {
        return new TripReadAccessSnapshot(2L, 1L, List.of(new TripReadAccessSnapshot.Member(
                3L, TripSharePermission.READ, Instant.parse("2026-11-06T00:00:00Z"))));
    }

    private AttachmentReadRefresh begin(
            TripAttachmentRedisCacheStore client,
            TripAttachmentRedisLockClient locks
    ) {
        RLock owner = locks.tryAcquire(key.tripId(), client.responseLockKey(key), 0L).orElseThrow();
        return client.beginResponseRefresh(key, owner);
    }

    private void replaceOwnerBeforeFirstCommit(StringRedisTemplate observed, String lockKey) {
        AtomicBoolean replaced = new AtomicBoolean();
        doAnswer(invocation -> {
            if (replaced.compareAndSet(false, true)) {
                try (RedisConnection other = connection.getConnection()) {
                    other.keyCommands().del(lockKey.getBytes(StandardCharsets.UTF_8));
                }
                assertThat(secondRedisson.getLock(lockKey).tryLock(0L, 10L, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(observed).exec();
    }

    private TripAttachmentRedisCacheStore secondStore() {
        return new TripAttachmentRedisCacheStore(template, json, properties, clock::get);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static RedissonClient newRedissonClient() {
        Config config = new Config();
        config.setThreads(2);
        config.setNettyThreads(2);
        config.useSingleServer().setAddress("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379))
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(4)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(4);
        return Redisson.create(config);
    }

    private TripAttachmentListResponse response(Long id) {
        return new TripAttachmentListResponse(
                List.of(new TripAttachmentListResponse.Item(id, "https://example.test/preview")), false, null, 4L);
    }
}
