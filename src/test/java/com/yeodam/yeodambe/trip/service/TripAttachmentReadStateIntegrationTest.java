package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.SharedTestMysql;
import com.yeodam.yeodambe.common.exception.PlaceFolderNotFoundException;
import com.yeodam.yeodambe.common.exception.TripDetailNotAvailableException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentRedisLockClient;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.config.TripAttachmentCacheProperties;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.RegionOrigin;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.service.request.AttachmentPageKey;
import com.yeodam.yeodambe.trip.service.response.AttachmentPageData;
import com.yeodam.yeodambe.trip.service.response.AttachmentReadRefresh;
import com.yeodam.yeodambe.trip.service.response.CachedAttachmentResponse;
import com.yeodam.yeodambe.trip.service.response.CachedTripReadAccessSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentListResponse;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.ServletWebRequest;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.generate_statistics=true", "app.photo-list-cache.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TripAccessService.class, TripEditAuthorizationService.class, TripAttachmentPageReader.class,
        TripAttachmentListService.class, TripAttachmentCacheProperties.class,
        TripAttachmentReadStateIntegrationTest.Config.class})
class TripAttachmentReadStateIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 12, 0);

    @Autowired
    private EntityManagerFactory entityManagers;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private TripRepository trips;
    @Autowired
    private TripShareUserRepository shares;
    @Autowired
    private UserRepository users;
    @Autowired
    private TripDetailPlaceRepository places;
    @Autowired
    private TripAttachmentRepository attachments;
    @Autowired
    private StoredFileRepository files;
    @Autowired
    private TripAttachmentPageReader reader;
    @Autowired
    private TripAttachmentListService service;
    @Autowired
    private TripAttachmentReadAccessService access;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AtomicLong nano;
    @Autowired
    private TripAttachmentCacheProperties properties;

    @MockitoBean
    private TripAttachmentRedisCacheStore responses;
    @MockitoBean
    private TripAttachmentRedisLockClient locks;
    @MockitoBean
    private TripAttachmentPageStore pages;
    @MockitoBean
    private TripAttachmentStorageClient storage;

    private Long ownerId;
    private Long readerId;
    private Long writerId;
    private Long tripId;
    private Long placeId;
    private Long photoId;
    private AttachmentPageKey key;

    @BeforeEach
    void prepare() {
        nano.set(0L);
        properties.setEnabled(true);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            ownerId = users.save(new User("owner-" + suffix + "@state.test", "소유자")).getUserId();
            readerId = users.save(new User("reader-" + suffix + "@state.test", "읽기")).getUserId();
            writerId = users.save(new User("writer-" + suffix + "@state.test", "편집")).getUserId();
            Trip trip = new Trip(ownerId, "상태 확인", LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6));
            ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
            tripId = trips.saveAndFlush(trip).getId();
            placeId = places.saveAndFlush(TripDetailPlace.fromAnalysis(tripId, 1, "장소",
                    BigDecimal.ZERO, BigDecimal.ZERO, NOW, NOW, "preview")).getId();
            shares.save(new TripShareUser(tripId, ownerId, readerId, TripSharePermission.READ, NOW.plusDays(1), NOW));
            shares.save(new TripShareUser(tripId, ownerId, writerId, TripSharePermission.WRITE, NOW.plusDays(1), NOW));
            photoId = savePhoto("first");
        });
        key = new AttachmentPageKey(tripId, placeId, null);
    }

    @Test
    void 요청에_연결된_EntityManager가_이전_시퀀스를_재사용하지_않는다() throws Exception {
        inRequest(() -> {
            preloadTrip();
            changeInAnotherRequest("update trips set change_sequence=99 where trip_id=?", tripId);
            assertThat(jdbc.queryForObject("select change_sequence from trips where trip_id=?", Long.class, tripId))
                    .isEqualTo(99L);
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                assertThat(executor.submit(() -> reader.readShared(key)).get(3, TimeUnit.SECONDS).sequence())
                        .isEqualTo(99L);
            }
            assertThat(reader.readShared(key).sequence()).isEqualTo(99L);
            verify(pages, times(2)).find(key, 99L);
        });
    }

    @Test
    void 시퀀스_증가_후_이전_메타데이터를_선택할_수_없다() throws Exception {
        AttachmentPageData old = new AttachmentPageData(List.of(
                new AttachmentPageData.Item(999L, NOW, "deleted-preview")), false, null, 0L);
        when(pages.find(key, 0L)).thenReturn(Optional.of(old));
        inRequest(() -> {
            preloadTrip();
            changeInAnotherRequest("update trips set change_sequence=99 where trip_id=?", tripId);
            AttachmentPageData current = reader.readShared(key);
            assertThat(current.items()).extracting(AttachmentPageData.Item::tripAttachmentId).containsExactly(photoId);
            assertThat(current.sequence()).isEqualTo(99L);
            verify(pages, never()).find(key, 0L);
        });
    }

    @Test
    void 현재_처리중_상태이면_이전에_완료된_여행도_거부한다() throws Exception {
        inRequest(() -> {
            preloadTrip();
            changeInAnotherRequest("update trips set processing_status='PROCESSING' where trip_id=?", tripId);
            assertThatThrownBy(() -> reader.readShared(key)).isInstanceOf(TripDetailNotAvailableException.class);
            assertThatThrownBy(() -> reader.validateTarget(tripId, placeId))
                    .isInstanceOf(TripDetailNotAvailableException.class);
            assertThatThrownBy(() -> reader.validateTarget(tripId, Long.MAX_VALUE))
                    .isInstanceOf(PlaceFolderNotFoundException.class);
        });
    }

    @Test
    void 현재_삭제된_상태이면_이전에_조회_가능했던_여행도_거부한다() throws Exception {
        inRequest(() -> {
            preloadTrip();
            changeInAnotherRequest("update trips set deleted_at=? where trip_id=?", NOW, tripId);
            assertThatThrownBy(() -> reader.readShared(key)).isInstanceOf(PlaceFolderNotFoundException.class);
        });
    }

    @Test
    void 요청에_연결된_EntityManager에서도_권한_스냅샷은_현재_소유자를_사용한다() throws Exception {
        stubCaches();
        inRequest(() -> {
            preloadTrip();
            changeInAnotherRequest("update trips set user_id=? where trip_id=?", writerId, tripId);
            assertThat(access.requireReadable(writerId, tripId, new AttachmentReadContext(nano::get)).userId())
                    .isEqualTo(writerId);
            assertThatThrownBy(() -> access.requireReadable(readerId, tripId, new AttachmentReadContext(nano::get)))
                    .isInstanceOf(PlaceFolderNotFoundException.class);
        });
    }

    @ParameterizedTest
    @CsvSource({
            "OWNER,COLD,1,5", "READ,COLD,1,5", "WRITE,COLD,1,5",
            "OWNER,COLD,19,5", "READ,COLD,19,5", "WRITE,COLD,19,5",
            "READ,METADATA_HIT,1,4", "WRITE,ACCESS_HIT,1,3",
            "OWNER,RESPONSE_HIT,1,0", "READ,RESPONSE_HIT,1,0", "WRITE,RESPONSE_HIT,19,0",
            "OWNER,OFF,1,3", "READ,OFF,1,4", "WRITE,OFF,1,4",
            "OWNER,OFF,19,3", "READ,OFF,19,4", "WRITE,OFF,19,4"
    })
    void 캐시_경로별_SELECT_횟수를_유지한다(AccessRole role, CacheState state, int photoCount, long expectedSelects) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (int index = 1; index < photoCount; index++) {
                savePhoto("photo-" + index);
            }
        });
        stubCaches();
        Long actor = switch (role) {
            case OWNER -> ownerId;
            case READ -> readerId;
            case WRITE -> writerId;
        };
        switch (state) {
            case COLD -> {
            }
            case METADATA_HIT -> when(pages.find(key, 0L)).thenReturn(Optional.of(new AttachmentPageData(
                    List.of(new AttachmentPageData.Item(photoId, NOW, "preview/first")), false, null, 0L)));
            case ACCESS_HIT -> access.requireReadable(actor, tripId, new AttachmentReadContext(nano::get));
            case RESPONSE_HIT -> service.findByPlaceFolder(ownerId, tripId, placeId, null);
            case OFF -> properties.setEnabled(false);
        }
        clearInvocations(storage, pages);
        Statistics statistics = entityManagers.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        TripAttachmentListResponse response = service.findByPlaceFolder(actor, tripId, placeId, null);

        assertThat(response.items()).hasSize(Math.min(photoCount, 18));
        assertThat(response.sequence()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(expectedSelects);
        if (state == CacheState.RESPONSE_HIT) {
            verifyNoInteractions(storage, pages);
        }
    }

    private void stubCaches() {
        AtomicReference<CachedTripReadAccessSnapshot> cachedAccess = new AtomicReference<>();
        AtomicReference<CachedAttachmentResponse> cachedResponse = new AtomicReference<>();
        RLock lock = mock(RLock.class);
        when(responses.accessLockKey(any())).thenReturn("access-lock");
        when(responses.responseLockKey(any())).thenReturn("response-lock");
        when(responses.findAccess(tripId)).thenAnswer(invocation -> Optional.ofNullable(cachedAccess.get())
                .filter(value -> value.isUsableAt(nano.get())));
        when(locks.tryAcquire(eq(tripId), eq("access-lock"), anyLong())).thenReturn(Optional.of(lock));
        when(responses.beginAccessRefresh(eq(tripId), any())).thenAnswer(invocation -> new AttachmentReadRefresh(
                "access-value", "access-lock", lock, "access", nano.get(), 0L, 495L));
        when(responses.publishAccess(eq(tripId), any(), any())).thenAnswer(invocation -> {
            AttachmentReadRefresh refresh = invocation.getArgument(1);
            cachedAccess.set(new CachedTripReadAccessSnapshot(invocation.getArgument(2),
                    refresh.startedAtNanos() + 495_000_000L, refresh.refreshId()));
            return TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED;
        });
        when(responses.findResponse(key)).thenAnswer(invocation -> Optional.ofNullable(cachedResponse.get())
                .filter(value -> value.isUsableAt(nano.get())));
        when(locks.tryAcquire(eq(tripId), eq("response-lock"), anyLong())).thenReturn(Optional.of(lock));
        when(responses.beginResponseRefresh(eq(key), any())).thenAnswer(invocation -> new AttachmentReadRefresh(
                "response-value", "response-lock", lock, "response", nano.get(), 0L, 495L));
        when(responses.publishResponse(eq(key), any(), anyLong(), any())).thenAnswer(invocation -> {
            AttachmentReadRefresh refresh = invocation.getArgument(1);
            cachedResponse.set(new CachedAttachmentResponse(invocation.getArgument(3),
                    refresh.startedAtNanos() + 495_000_000L, refresh.refreshId(), invocation.getArgument(2)));
            return TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED;
        });
        when(storage.createReadUrl(anyString())).thenAnswer(invocation -> "https://state.test/" + invocation.getArgument(0));
    }

    private enum AccessRole {
        OWNER,
        READ,
        WRITE
    }

    private enum CacheState {
        COLD,
        METADATA_HIT,
        ACCESS_HIT,
        RESPONSE_HIT,
        OFF
    }

    private void preloadTrip() {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.setReadOnly(true);
        transaction.executeWithoutResult(status -> trips.findByIdAndDeletedAtIsNull(tripId).orElseThrow());
    }

    private void changeInAnotherRequest(String sql, Object... arguments) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> new TransactionTemplate(transactions)
                    .executeWithoutResult(status -> jdbc.update(sql, arguments))).get(3, TimeUnit.SECONDS);
        }
    }

    private void inRequest(RequestAction action) throws Exception {
        OpenEntityManagerInViewInterceptor interceptor = new OpenEntityManagerInViewInterceptor();
        interceptor.setEntityManagerFactory(entityManagers);
        ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest());
        interceptor.preHandle(request);
        try {
            action.run();
        } finally {
            interceptor.afterCompletion(request, null);
            assertThat(TransactionSynchronizationManager.hasResource(entityManagers)).isFalse();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }
    }

    private Long savePhoto(String suffix) {
        StoredFile file = files.saveAndFlush(StoredFile.uploaded(ownerId, "photo.jpg",
                UUID.randomUUID() + "/" + suffix, "image/jpeg"));
        TripAttachment photo = TripAttachment.initial(tripId, file.getId(), "analysis/" + suffix, "preview/" + suffix);
        photo.classify(placeId, RegionOrigin.EXIF, NOW, BigDecimal.ZERO, BigDecimal.ZERO, 80);
        return attachments.saveAndFlush(photo).getId();
    }

    @FunctionalInterface
    private interface RequestAction {
        void run() throws Exception;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        JdbcConnectionDetails mysql() throws SQLException {
            return SharedTestMysql.createDatabase();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        AtomicLong nanoTime() {
            return new AtomicLong();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-05T03:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        TripAttachmentReadAccessService readAccess(
                TripRepository trips,
                TripShareUserRepository shares,
                TripAttachmentRedisCacheStore store,
                TripAttachmentRedisLockClient locks,
                PlatformTransactionManager transactions,
                Clock clock,
                AtomicLong nano
        ) {
            return new TripAttachmentReadAccessService(trips, shares, store, locks, transactions, clock, nano::get);
        }

        @Bean
        TripAttachmentResponseCacheService responseCache(
                TripAttachmentPageReader reader,
                TripAttachmentRedisCacheStore store,
                TripAttachmentRedisLockClient locks,
                TripAttachmentReadAccessService access,
                ApplicationEventPublisher events,
                AtomicLong nano
        ) {
            return new TripAttachmentResponseCacheService(reader, store, locks, access, events, nano::get);
        }
    }
}
