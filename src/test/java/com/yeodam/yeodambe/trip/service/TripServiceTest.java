package com.yeodam.yeodambe.trip.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yeodam.yeodambe.common.exception.InvalidTripRequestException;
import com.yeodam.yeodambe.common.exception.TripNameDuplicatedException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripRegion;
import com.yeodam.yeodambe.trip.repository.TripAttachmentCount;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.RegionCatalog;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class TripServiceTest {
    private TripRepository tripRepository;
    private TripRegionRepository tripRegionRepository;
    private TripAttachmentRepository tripAttachmentRepository;
    private TripAttachmentStorageClient tripAttachmentStorageClient;
    private RegionCatalog regionCatalog;
    private TripAccessService tripAccessService;
    private TripService tripService;
    private TripMapCacheStore mapCache;
    private final Logger logger = (Logger) LoggerFactory.getLogger(TripService.class);
    private ListAppender<ILoggingEvent> logAppender;

    @AfterEach
    void detachLogAppender() {
        if (logAppender != null) {
            logger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    @BeforeEach
    void setUp() {
        tripRepository = mock(TripRepository.class);
        tripRegionRepository = mock(TripRegionRepository.class);
        tripAttachmentRepository = mock(TripAttachmentRepository.class);
        tripAttachmentStorageClient = mock(TripAttachmentStorageClient.class);
        regionCatalog = mock(RegionCatalog.class);
        tripAccessService = mock(TripAccessService.class);
        mapCache = mock(TripMapCacheStore.class);
        when(mapCache.version(1L)).thenReturn(Optional.of("v1"));
        tripService = new TripService(mock(com.yeodam.yeodambe.trip.service.AdditionalAttachmentModificationGuard.class),
                tripRepository,
                tripRegionRepository,
                mock(com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository.class),
                regionCatalog,
                tripAttachmentRepository,
                tripAttachmentStorageClient,
                tripAccessService,
                new ObjectMapper(),
                mock(StoryRepository.class),
                mapCache
        );
    }

    @Test
    void 메타데이터_없이_여행과_지역을_저장한다() {
        givenRegion();
        givenSavedTrip();

        var response = tripService.createTrip(1L,
                request(LocalDate.now(), LocalDate.now(), List.of("50110")));

        assertThat(response.tripId()).isEqualTo(7L);
        assertThat(response.status()).isEqualTo(ProcessingStatus.PROCESSING);
        verify(tripRegionRepository).saveAll(anyList());
        verifyNoInteractions(tripAttachmentRepository, tripAttachmentStorageClient);
    }

    @Test
    void 완료된_여행_상세를_조회하면_성공_로그를_남긴다() {
        startLogAppender();
        Trip trip = mock(Trip.class);
        when(trip.getId()).thenReturn(7L);
        when(trip.getTripName()).thenReturn("제주 여행");
        when(trip.getStartDate()).thenReturn(LocalDate.of(2026, 9, 1));
        when(trip.getEndDate()).thenReturn(LocalDate.of(2026, 9, 3));
        when(trip.getProcessingStatus()).thenReturn(ProcessingStatus.COMPLETED);
        when(trip.getFavorite()).thenReturn(true);
        when(tripAccessService.requireReadableTrip(7L, 1L)).thenReturn(trip);
        when(tripRegionRepository.findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(7L))
                .thenReturn(List.of());
        when(tripAttachmentRepository.countActiveByTripId(7L)).thenReturn(3L);

        var response = tripService.findTripDetail(7L, 1L);

        assertThat(response.attachmentCount()).isEqualTo(3L);
        ILoggingEvent success = logAppender.list.stream()
                .filter(event -> "trip_view".equals(keyValue(event, "event")))
                .filter(event -> "success".equals(keyValue(event, "result")))
                .findFirst()
                .orElseThrow();
        assertThat(keyValue(success, "trip_id")).isEqualTo(7L);
        assertThat(keyValue(success, "attachment_count")).isEqualTo(3L);
    }

    @Test
    void 조회할_수_없는_여행이면_예상_실패_로그를_남긴다() {
        startLogAppender();
        when(tripAccessService.requireReadableTrip(7L, 1L))
                .thenThrow(new TripNotFoundException());

        assertThrows(TripNotFoundException.class,
                () -> tripService.findTripDetail(7L, 1L));

        ILoggingEvent failure = logAppender.list.stream()
                .filter(event -> "trip_view".equals(keyValue(event, "event")))
                .filter(event -> "failure".equals(keyValue(event, "result")))
                .findFirst()
                .orElseThrow();
        assertThat(failure.getLevel()).isEqualTo(Level.WARN);
        assertThat(keyValue(failure, "error_code")).isEqualTo("TRIP_NOT_FOUND");
    }

    @Test
    void 종료일이_빠르거나_날짜가_미래면_거부한다() {
        LocalDate today = LocalDate.now();

        assertInvalid(today, today.minusDays(1), List.of("50110"));
        assertInvalid(today.plusDays(1), today.plusDays(1), List.of("50110"));
        assertInvalid(today, today.plusDays(1), List.of("50110"));
        verifyNoInteractions(tripRepository, tripRegionRepository);
    }

    @Test
    void 시작일을_포함해_구십삼일이면_거부한다() {
        assertInvalid(LocalDate.now().minusDays(92), LocalDate.now(), List.of("50110"));
    }

    @Test
    void 같은_지역을_두번_선택하면_거부한다() {
        assertInvalid(LocalDate.now(), LocalDate.now(), List.of("50110", "50110"));
    }

    @Test
    void 없는_지역_코드는_거부한다() {
        when(regionCatalog.getRequired("99999")).thenThrow(new InvalidTripRequestException());

        assertThrows(InvalidTripRequestException.class,
                () -> tripService.createTrip(1L, request(LocalDate.now(), LocalDate.now(), List.of("99999"))));
        verifyNoInteractions(tripRepository, tripRegionRepository);
    }

    @Test
    void 같은_사용자의_활성_여행명이_있으면_거부한다() {
        givenRegion();
        when(tripRepository.existsByUserIdAndTripNameAndDeletedAtIsNull(1L, "여행"))
                .thenReturn(true);

        assertThrows(TripNameDuplicatedException.class,
                () -> tripService.createTrip(1L, request(LocalDate.now(), LocalDate.now(), List.of("50110"))));
        verify(tripRepository, never()).save(any());
    }

    @Test
    void 당일치기는_저장한다() {
        givenRegion();
        givenSavedTrip();

        var sameDay = tripService.createTrip(1L,
                request(LocalDate.now(), LocalDate.now(), List.of("50110")));

        assertThat(sameDay.tripId()).isEqualTo(7L);
        assertThat(sameDay.status()).isEqualTo(ProcessingStatus.PROCESSING);
        verify(tripRegionRepository).saveAll(anyList());
    }

    @Test
    void 시작일을_포함한_구십이일은_저장한다() {
        givenRegion();
        givenSavedTrip();

        var response = tripService.createTrip(1L,
                request(LocalDate.now().minusDays(91), LocalDate.now(), List.of("50110")));

        assertThat(response.tripId()).isEqualTo(7L);
        assertThat(response.status()).isEqualTo(ProcessingStatus.PROCESSING);
        verify(tripRegionRepository).saveAll(anyList());
    }

    @Test
    void 완료된_소유_여행을_즐겨찾기로_등록한다() {
        Trip trip = new Trip(1L, "여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "id", 7L);
        when(tripRepository.findByIdAndUserIdAndProcessingStatusAndDeletedAtIsNull(
                7L, 1L, ProcessingStatus.COMPLETED)).thenReturn(Optional.of(trip));

        var response = tripService.registerFavorite(7L, 1L);

        assertThat(trip.getFavorite()).isTrue();
        assertThat(response.tripId()).isEqualTo(7L);
        assertThat(response.isFavorite()).isTrue();
    }

    @Test
    void 이미_즐겨찾기인_여행도_true로_유지한다() {
        Trip trip = new Trip(1L, "여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "id", 7L);
        ReflectionTestUtils.setField(trip, "favorite", true);
        when(tripRepository.findByIdAndUserIdAndProcessingStatusAndDeletedAtIsNull(
                7L, 1L, ProcessingStatus.COMPLETED)).thenReturn(Optional.of(trip));

        var response = tripService.registerFavorite(7L, 1L);

        assertThat(trip.getFavorite()).isTrue();
        assertThat(response.isFavorite()).isTrue();
    }

    @Test
    void 등록할_수_있는_여행이_없으면_찾을_수_없음으로_처리한다() {
        when(tripRepository.findByIdAndUserIdAndProcessingStatusAndDeletedAtIsNull(
                7L, 1L, ProcessingStatus.COMPLETED)).thenReturn(Optional.empty());

        assertThrows(TripNotFoundException.class,
                () -> tripService.registerFavorite(7L, 1L));
    }

    @Test
    void 즐겨찾기_삭제를_반복해도_false로_유지한다() {
        Trip trip = new Trip(1L, "여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "favorite", true);
        when(tripRepository.findByIdAndUserIdAndProcessingStatusAndDeletedAtIsNull(
                7L, 1L, ProcessingStatus.COMPLETED)).thenReturn(Optional.of(trip));

        tripService.removeFavorite(7L, 1L);
        tripService.removeFavorite(7L, 1L);

        assertThat(trip.getFavorite()).isFalse();
    }

    @Test
    void 삭제할_수_있는_여행이_없으면_찾을_수_없음으로_처리한다() {
        when(tripRepository.findByIdAndUserIdAndProcessingStatusAndDeletedAtIsNull(
                7L, 1L, ProcessingStatus.COMPLETED)).thenReturn(Optional.empty());

        assertThrows(TripNotFoundException.class,
                () -> tripService.removeFavorite(7L, 1L));
    }

    @Test
    void sameCoordinatesRemainSeparateMarkersForEachTrip() {
        when(tripAttachmentRepository.findMapRepresentatives(1L))
                .thenReturn(List.of(mapRepresentative(1L, "first-preview"), mapRepresentative(2L, "second-preview")));
        var response = tripService.findMap(1L);
        assertThat(response.markers()).hasSize(2);
        assertThat(response.markers()).extracting(marker -> marker.tripId()).containsExactly(1L, 2L);
        assertThat(response.markers()).allSatisfy(marker -> {
            assertThat(marker.latitude()).isEqualByComparingTo("33.4996");
            assertThat(marker.longitude()).isEqualByComparingTo("126.5312");
        });
        verifyNoInteractions(tripRegionRepository, tripRepository);
    }

    @Test
    void returnsExistingAttachmentCountDirectlyOnMarker() {
        when(tripAttachmentRepository.findMapRepresentatives(1L)).thenReturn(List.of(mapRepresentative(1L, "preview")));
        when(tripAttachmentRepository.countNotDeletedByTripIds(List.of(1L)))
                .thenReturn(List.of(new TripAttachmentCount(1L, 3L)));
        var response = tripService.findMap(1L);
        assertThat(response.markers().getFirst().attachmentCount()).isEqualTo(3L);
    }

    @Test
    void usesSelectedPhotoPreviewWithoutReadingOrChangingTripThumbnail() {
        when(tripAttachmentRepository.findMapRepresentatives(1L)).thenReturn(List.of(mapRepresentative(1L, "selected-preview")));
        when(tripAttachmentStorageClient.createReadUrl("selected-preview")).thenReturn("https://example.test/selected");
        var response = tripService.findMap(1L);
        assertThat(response.markers().getFirst().thumbnailUrl()).isEqualTo("https://example.test/selected");
        verify(tripAttachmentStorageClient).createReadUrl("selected-preview");
        verifyNoInteractions(tripRepository, tripRegionRepository);
    }

    @Test
    void returnsEmptyMarkersWithoutCountOrUrlCallsWhenNoCoordinateCandidatesExist() {
        when(tripAttachmentRepository.findMapRepresentatives(1L)).thenReturn(List.of());
        assertThat(tripService.findMap(1L).markers()).isEmpty();
        verify(tripAttachmentRepository, never()).countNotDeletedByTripIds(anyList());
        verifyNoInteractions(tripAttachmentStorageClient, tripRegionRepository);
    }

    @Test
    void cachedMapSkipsDatabaseAndCreatesFreshUrlsOnEachRequest() {
        var cached = new com.yeodam.yeodambe.trip.service.response.TripMapCacheData(List.of(
                new com.yeodam.yeodambe.trip.service.response.TripMapCacheData.Marker(
                        7L, "cached-trip", BigDecimal.ONE, BigDecimal.TEN, "preview-key", 3L)));
        when(mapCache.find(1L, "v1")).thenReturn(Optional.of(cached));
        when(tripAttachmentStorageClient.createReadUrl("preview-key"))
                .thenReturn("https://example.test/first", "https://example.test/second");

        var first = tripService.findMap(1L);
        var second = tripService.findMap(1L);

        assertThat(first.markers().getFirst().thumbnailUrl()).isEqualTo("https://example.test/first");
        assertThat(second.markers().getFirst().thumbnailUrl()).isEqualTo("https://example.test/second");
        assertThat(first.markers().getFirst().attachmentCount()).isEqualTo(3);
        verifyNoInteractions(tripAttachmentRepository);
        verify(mapCache, never()).save(anyLong(), anyString(), any());
    }

    @Test
    void cacheMissLoadsDatabaseAndStoresObjectKeyInsteadOfSignedUrl() {
        when(mapCache.find(1L, "v1")).thenReturn(Optional.empty());
        when(tripAttachmentRepository.findMapRepresentatives(1L))
                .thenReturn(List.of(mapRepresentative(7L, "preview-key")));
        when(tripAttachmentRepository.countNotDeletedByTripIds(List.of(7L)))
                .thenReturn(List.of(new TripAttachmentCount(7L, 3L)));
        when(tripAttachmentStorageClient.createReadUrl("preview-key"))
                .thenReturn("https://example.test/signed");

        var response = tripService.findMap(1L);

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.yeodam.yeodambe.trip.service.response.TripMapCacheData.class);
        verify(mapCache).save(eq(1L), eq("v1"), captor.capture());
        assertThat(captor.getValue().markers().getFirst().previewStorageKey()).isEqualTo("preview-key");
        assertThat(captor.getValue().markers().getFirst().attachmentCount()).isEqualTo(3);
        assertThat(response.markers().getFirst().thumbnailUrl()).isEqualTo("https://example.test/signed");
        verify(mapCache).find(1L, "v1");
        verify(tripAttachmentRepository).findMapRepresentatives(1L);
    }

    @Test
    void emptyMapIsAlsoCached() {
        when(tripAttachmentRepository.findMapRepresentatives(1L)).thenReturn(List.of());
        assertThat(tripService.findMap(1L).markers()).isEmpty();
        verify(mapCache).save(eq(1L), eq("v1"), eq(
                new com.yeodam.yeodambe.trip.service.response.TripMapCacheData(List.of())));
    }

    @Test
    void unavailableVersionBypassesCacheAndReadsDatabase() {
        when(mapCache.version(1L)).thenReturn(Optional.empty());
        when(tripAttachmentRepository.findMapRepresentatives(1L))
                .thenReturn(List.of(mapRepresentative(7L, "preview")));
        var response = tripService.findMap(1L);
        assertThat(response.markers()).hasSize(1);
        verify(mapCache, never()).find(anyLong(), anyString());
        verify(mapCache, never()).save(anyLong(), anyString(), any());
        verify(tripAttachmentRepository).findMapRepresentatives(1L);
    }

    @Test
    void concurrentCacheMissesMayLoadDatabaseIndependently() throws Exception {
        var gate = new java.util.concurrent.CyclicBarrier(2);
        when(tripAttachmentRepository.findMapRepresentatives(1L)).thenAnswer(call -> {
            gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return List.of();
        });

        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> tripService.findMap(1L));
            var second = pool.submit(() -> tripService.findMap(1L));

            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS).markers()).isEmpty();
            assertThat(second.get(10, java.util.concurrent.TimeUnit.SECONDS).markers()).isEmpty();
        }

        verify(mapCache, times(2)).find(1L, "v1");
        verify(tripAttachmentRepository, times(2)).findMapRepresentatives(1L);
        verify(mapCache, times(2)).save(eq(1L), eq("v1"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisReadAndWriteFailuresDoNotPreventMapResponse() {
        var redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        var values = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("cache:trip-map:version:user:1")).thenReturn("v1");
        when(values.get("cache:trip-map:user:1:v1"))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("redis read unavailable"));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("redis write unavailable"))
                .when(values).set(anyString(), anyString(), any(java.time.Duration.class));
        ReflectionTestUtils.setField(tripService, "tripMapCacheStore",
                new TripMapCacheStore(redis, new ObjectMapper()));
        when(tripAttachmentRepository.findMapRepresentatives(1L))
                .thenReturn(List.of(mapRepresentative(7L, "preview-key")));
        when(tripAttachmentRepository.countNotDeletedByTripIds(List.of(7L)))
                .thenReturn(List.of(new TripAttachmentCount(7L, 3L)));
        when(tripAttachmentStorageClient.createReadUrl("preview-key"))
                .thenReturn("https://example.test/signed");

        var response = tripService.findMap(1L);

        assertThat(response.markers()).singleElement().satisfies(marker -> {
            assertThat(marker.tripId()).isEqualTo(7L);
            assertThat(marker.attachmentCount()).isEqualTo(3L);
            assertThat(marker.thumbnailUrl()).isEqualTo("https://example.test/signed");
        });
        verify(tripAttachmentRepository).findMapRepresentatives(1L);
        verify(values).set(eq("cache:trip-map:user:1:v1"), anyString(), eq(java.time.Duration.ofSeconds(60)));
    }

    @Test
    void databaseFailurePropagatesWithoutSavingCache() {
        var failure = new org.springframework.dao.DataAccessResourceFailureException("database unavailable");
        when(tripAttachmentRepository.findMapRepresentatives(1L)).thenThrow(failure);

        assertThat(assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> tripService.findMap(1L))).isSameAs(failure);

        verify(mapCache, never()).save(anyLong(), anyString(), any());
    }

    private com.yeodam.yeodambe.trip.repository.TripMapRepresentative mapRepresentative(Long tripId, String previewKey) {
        return new com.yeodam.yeodambe.trip.repository.TripMapRepresentative(tripId, "trip-" + tripId,
                new BigDecimal("33.4996"), new BigDecimal("126.5312"), previewKey);
    }

    private void assertInvalid(
            LocalDate start,
            LocalDate end,
            List<String> codes
    ) {
        assertThrows(InvalidTripRequestException.class,
                () -> tripService.createTrip(1L, request(start, end, codes)));
    }

    private TripCreateRequest request(
            LocalDate start,
            LocalDate end,
            List<String> codes
    ) {
        return new TripCreateRequest(
                "여행",
                start,
                end,
                codes
        );
    }

    private void givenRegion() {
        when(regionCatalog.getRequired("50110")).thenReturn(new RegionCatalog.Region(
                "50110", "제주특별자치도 제주시", new BigDecimal("33.5"), new BigDecimal("126.5")));
    }

    private void givenSavedTrip() {
        when(tripRepository.save(any(Trip.class))).thenAnswer(invocation -> {
            Trip trip = invocation.getArgument(0);
            ReflectionTestUtils.setField(trip, "id", 7L);
            return trip;
        });
    }

    private void startLogAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logAppender = appender;
    }

    private Object keyValue(
            ILoggingEvent event,
            String key
    ) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> key.equals(pair.key))
                .map(pair -> pair.value)
                .findFirst()
                .orElse(null);
    }
}
