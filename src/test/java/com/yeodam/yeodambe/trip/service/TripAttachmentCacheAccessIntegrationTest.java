package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.PlaceFolderNotFoundException;
import com.yeodam.yeodambe.trip.client.TripAttachmentRedisLockClient;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.config.TripAttachmentCacheProperties;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.service.request.AttachmentPageQuery;
import com.yeodam.yeodambe.trip.service.response.AttachmentReadRefresh;
import com.yeodam.yeodambe.trip.service.response.CachedAttachmentResponse;
import com.yeodam.yeodambe.trip.service.response.CachedTripReadAccessSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentListResponse;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:${random.uuid};MODE=MySQL",
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "app.photo-list-cache.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TripAccessService.class, TripEditAuthorizationService.class, TripAttachmentPageReader.class,
        TripAttachmentListService.class, TripAttachmentCacheProperties.class,
        TripAttachmentCacheAccessIntegrationTest.Config.class})
class TripAttachmentCacheAccessIntegrationTest {

    @MockitoBean
    private TripAttachmentPageStore pages;
    @MockitoBean
    private TripAttachmentRedisCacheStore responses;
    @MockitoBean
    private TripAttachmentRedisLockClient locks;
    @MockitoBean
    private TripAttachmentStorageClient storage;
    @MockitoBean
    private TripDetailPlaceRepository places;
    @MockitoBean
    private TripAttachmentRepository attachments;

    @Autowired
    private TripAttachmentListService service;
    @Autowired
    private UserRepository users;
    @Autowired
    private TripRepository trips;
    @Autowired
    private TripShareUserRepository shares;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private AtomicLong clock;

    private Trip trip;
    private Long actorId;
    private Long writerId;
    private final AtomicReference<CachedAttachmentResponse> cached = new AtomicReference<>();

    private final AtomicReference<CachedTripReadAccessSnapshot> cachedAccess = new AtomicReference<>();

    @BeforeEach
    void prepare() {
        clock.set(0L);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            Long ownerId = users.save(new User("owner-" + suffix + "@test.com", "소유자")).getUserId();
            actorId = users.save(new User("actor-" + suffix + "@test.com", "공유사용자")).getUserId();
            writerId = users.save(new User("writer-" + suffix + "@test.com", "편집자")).getUserId();
            trip = new Trip(ownerId, "공유 여행", LocalDate.now(), LocalDate.now());
            ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
            trips.saveAndFlush(trip);
            shares.saveAndFlush(new TripShareUser(trip.getId(), ownerId, actorId,
                    TripSharePermission.READ, LocalDateTime.of(2026, 11, 5, 12, 0),
                    LocalDateTime.of(2026, 11, 4, 12, 0)));
            shares.saveAndFlush(new TripShareUser(trip.getId(), ownerId, writerId,
                    TripSharePermission.WRITE, LocalDateTime.of(2026, 11, 5, 12, 0),
                    LocalDateTime.of(2026, 11, 4, 12, 0)));
        });
        when(responses.accessLockKey(any())).thenReturn("access-lock");
        when(responses.responseLockKey(any())).thenReturn("response-lock");
        when(responses.findAccess(any())).thenAnswer(invocation -> Optional.ofNullable(cachedAccess.get())
                .filter(value -> value.isUsableAt(clock.get())));
        when(locks.tryAcquire(any(), eq("access-lock"), anyLong())).thenReturn(Optional.of(mock(RLock.class)));
        when(responses.beginAccessRefresh(any(), any())).thenAnswer(invocation -> new AttachmentReadRefresh(
                "access-value", "access-lock", invocation.getArgument(1), "access", clock.get(), 0L, 495L));
        when(responses.publishAccess(any(), any(), any())).thenAnswer(invocation -> {
            AttachmentReadRefresh refresh = invocation.getArgument(1);
            cachedAccess.set(new CachedTripReadAccessSnapshot(invocation.getArgument(2),
                    refresh.startedAtNanos() + 495_000_000L, "access"));
            return TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED;
        });
        TripAttachment photo = mock(TripAttachment.class);
        when(photo.getId()).thenReturn(10L);
        when(photo.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 11, 4, 12, 0));
        when(photo.getPreviewStorageKey()).thenReturn("preview");
        when(attachments.findByPlaceFolderWithCursor(any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(photo));
        when(storage.createReadUrl("preview")).thenReturn("https://example.test/preview");
        when(places.existsByIdAndTripIdAndDeletedAtIsNull(3L, trip.getId())).thenReturn(true);
        when(responses.findResponse(any())).thenAnswer(invocation -> Optional.ofNullable(cached.get())
                .filter(value -> value.isUsableAt(clock.get())));
        when(locks.tryAcquire(any(), eq("response-lock"), anyLong())).thenReturn(Optional.of(mock(RLock.class)));
        when(responses.beginResponseRefresh(any(), any())).thenAnswer(invocation -> new AttachmentReadRefresh(
                "value", "lock", invocation.getArgument(1), "test", clock.get(), 0L, 495L));
        when(responses.publishResponse(any(), any(), anyLong(), any())).thenAnswer(invocation -> {
            AttachmentReadRefresh refresh = invocation.getArgument(1);
            cached.set(new CachedAttachmentResponse(invocation.getArgument(3),
                    refresh.startedAtNanos() + 495_000_000L, "test", invocation.getArgument(2)));
            return TripAttachmentRedisCacheStore.PublishOutcome.PUBLISHED;
        });
    }

    @Test
    void 공유_조회_응답을_잠시_재사용하되_재사용_기간_후_철회된_권한을_거부한다() {
        TripAttachmentListResponse first = service.findByPlaceFolder(actorId, trip.getId(), 3L, null);
        assertThat(first.sequence()).isEqualTo(trip.getSequence());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            TripShareUser share = shares.findByTripIdAndGranteeUserId(trip.getId(), actorId).orElseThrow();
            ReflectionTestUtils.setField(share, "deletedAt", LocalDateTime.of(2026, 11, 4, 12, 0));
            shares.saveAndFlush(share);
        });

        clock.set(100_000_000L);
        assertThat(service.findByPlaceFolder(actorId, trip.getId(), 3L, null)).isSameAs(first);
        clock.set(500_000_000L);
        assertThatThrownBy(() -> service.findByPlaceFolder(actorId, trip.getId(), 3L, null))
                .isInstanceOf(PlaceFolderNotFoundException.class);
    }

    @Test
    void 권한_없는_사용자는_이미_캐시된_응답도_조회할_수_없다() {
        service.findByPlaceFolder(actorId, trip.getId(), 3L, null);

        assertThatThrownBy(() -> service.findByPlaceFolder(actorId + 1000L, trip.getId(), 3L, null))
                .isInstanceOf(PlaceFolderNotFoundException.class);
    }

    @Test
    void 서로_다른_권한_보유자가_같은_응답을_재사용한다() {
        TripAttachmentListResponse first = service.findByPlaceFolder(actorId, trip.getId(), 3L, null);
        assertThat(service.findByPlaceFolder(writerId, trip.getId(), 3L, null)).isSameAs(first);
        assertThat(service.findByPlaceFolder(trip.getUserId(), trip.getId(), 3L, null)).isSameAs(first);
    }

    @Test
    void 응답_대기_중의_권한_철회를_반환_전에_반영한다() throws Exception {
        CountDownLatch mapping = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(locks.tryAcquire(any(), eq("response-lock"), anyLong())).thenAnswer(invocation -> {
            clock.set(400_000_000L);
            return Optional.of(mock(RLock.class));
        });
        when(storage.createReadUrl("preview")).thenAnswer(invocation -> {
            mapping.countDown();
            assertThat(finish.await(3, TimeUnit.SECONDS)).isTrue();
            return "https://example.test/preview";
        });
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TripAttachmentListResponse> result = executor.submit(() ->
                    service.findByPlaceFolder(actorId, trip.getId(), 3L, null));
            try {
                assertThat(mapping.await(3, TimeUnit.SECONDS)).isTrue();
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    TripShareUser share = shares.findByTripIdAndGranteeUserId(trip.getId(), actorId).orElseThrow();
                    ReflectionTestUtils.setField(share, "deletedAt", LocalDateTime.of(2026, 11, 4, 12, 0));
                    shares.saveAndFlush(share);
                });
                clock.set(600_000_000L);
                finish.countDown();
                assertThatThrownBy(() -> result.get(3, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(PlaceFolderNotFoundException.class);
                assertThat(cached.get().validUntilNanos()).isGreaterThan(clock.get());
            } finally {
                finish.countDown();
            }
        }
    }

    @Test
    void ACL과_응답_캐시_적중시_사진_서비스와_DB와_URL_생성을_생략한다() {
        TripAttachmentListResponse first = service.findByPlaceFolder(actorId, trip.getId(), 3L, null);
        clearInvocations(attachments, storage, responses, locks);
        assertThat(service.findByPlaceFolder(writerId, trip.getId(), 3L, null)).isSameAs(first);
        verifyNoInteractions(attachments, storage);
        verify(responses, times(1)).findAccess(trip.getId());
        verify(locks, never()).tryAcquire(any(), eq("access-lock"), anyLong());
        verify(locks, never()).tryAcquire(any(), eq("response-lock"), anyLong());
    }

    @TestConfiguration
    static class Config {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        Clock accessClock() {
            return Clock.fixed(Instant.parse("2026-11-04T03:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        AtomicLong responseClock() {
            return new AtomicLong();
        }

        @Bean
        TripAttachmentReadAccessService readAccessService(
                TripRepository trips,
                TripShareUserRepository shares,
                TripAttachmentRedisCacheStore store,
                TripAttachmentRedisLockClient locks,
                PlatformTransactionManager transactions,
                Clock wall,
                AtomicLong nano
        ) {
            return new TripAttachmentReadAccessService(trips, shares, store, locks, transactions, wall, nano::get);
        }

        @Bean
        TripAttachmentResponseCacheService responseService(
                TripAttachmentPageReader reader,
                TripAttachmentRedisCacheStore responses,
                TripAttachmentRedisLockClient locks,
                TripAttachmentReadAccessService access,
                ApplicationEventPublisher events,
                AtomicLong clock
        ) {
            return new TripAttachmentResponseCacheService(reader, responses, locks, access, events, clock::get);
        }
    }
}
