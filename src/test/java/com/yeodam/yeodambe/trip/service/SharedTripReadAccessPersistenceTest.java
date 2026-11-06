package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentIdsException;
import com.yeodam.yeodambe.common.exception.StoryNotFoundException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.story.service.StoryReadService;
import com.yeodam.yeodambe.story.service.StoryValidator;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.RegionOrigin;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.story.repository.StoryBlockReferenceQuery;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true", showSql = false)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class,
        StoryBlockReferenceQuery.class,
        JacksonAutoConfiguration.class, TripAccessService.class, TripEditAuthorizationService.class,
        StoryReadService.class, StoryValidator.class, TripAttachmentDownloadService.class,
        BulkAttachmentDownloadService.class, TripService.class, InvitedTripListService.class,
        SharedTripReadAccessPersistenceTest.TimeConfig.class})
class SharedTripReadAccessPersistenceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 12, 0);

    @Autowired private EntityManager em;
    @Autowired private StoryReadService stories;
    @Autowired private TripAttachmentDownloadService downloads;
    @Autowired private BulkAttachmentDownloadService bulkDownloads;
    @Autowired private TripService tripService;
    @Autowired private InvitedTripListService invitedTrips;
    @MockitoBean private AdditionalAttachmentModificationGuard modificationGuard;
    @MockitoBean private RegionCatalog regionCatalog;
    @MockitoBean private TripMapCacheStore mapCache;
    @MockitoBean private TripAttachmentStorageClient storage;
    @MockitoBean private StoryContentRepository contents;

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean
        tools.jackson.databind.ObjectMapper objectMapper() {
            return new tools.jackson.databind.ObjectMapper();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-02T03:00:00Z"), ZoneOffset.UTC);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "READ", "WRITE"})
    void 소유자와_유효한_공유자는_스토리와_사진을_읽을_수_있다(String permission) throws Exception {
        Fixture fixture = fixture();
        Long actorId = actor(fixture, permission);
        flushAndClear();
        stubStorage();

        assertThat(stories.findStory(actorId, fixture.trip().getId()).userByMe())
                .isEqualTo(permission.equals("OWNER"));
        assertThat(downloads.issueDownloadUrl(actorId, fixture.first().getId()).downloadUrl())
                .isEqualTo("https://download.test");
        assertThat(bulkDownloads.issueDownloadUrl(actorId, fixture.photoIds()).downloadUrl())
                .isEqualTo("https://download.test");
        verify(storage).createReadUrl("preview-first");
        verify(storage).createDownloadUrl("original-first", "first.heic");
        verify(storage).createDownloadUrl("archive", "yeodam-attachments.zip");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "AT_EXPIRY", "EXPIRED", "REVOKED", "WRONG_ISSUER", "DELETED_TRIP"})
    void 무효한_공유는_세_조회에서_거절한다(String reason) {
        Fixture fixture = fixture();
        if (!reason.equals("NONE")) {
            TripShareUser share = share(fixture, TripSharePermission.READ);
            switch (reason) {
                case "AT_EXPIRY" -> ReflectionTestUtils.setField(share, "expiresAt", NOW);
                case "EXPIRED" -> ReflectionTestUtils.setField(share, "expiresAt", NOW.minusSeconds(1));
                case "REVOKED" -> ReflectionTestUtils.setField(share, "deletedAt", NOW.minusMinutes(1));
                case "WRONG_ISSUER" -> ReflectionTestUtils.setField(share, "userId", fixture.reader().getUserId());
                case "DELETED_TRIP" -> fixture.trip().softDelete(NOW);
                default -> throw new IllegalArgumentException(reason);
            }
        }
        flushAndClear();

        assertDenied(fixture, fixture.reader().getUserId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "READ", "WRITE"})
    void 삭제_여행은_소유자와_공유자_모두_거절한다(String permission) {
        Fixture fixture = fixture();
        Long actorId = actor(fixture, permission);
        fixture.trip().softDelete(NOW);
        flushAndClear();

        assertDenied(fixture, actorId);
    }

    @ParameterizedTest
    @CsvSource({"OWNER,PHOTO", "READ,PHOTO", "WRITE,PHOTO", "OWNER,FILE", "READ,FILE", "WRITE,FILE"})
    void 삭제_사진과_파일은_다운로드에서_제외한다(String permission, String deleted) {
        Fixture fixture = fixture();
        Long actorId = actor(fixture, permission);
        if (deleted.equals("PHOTO")) {
            fixture.first().softDelete(NOW);
        } else {
            em.find(StoredFile.class, fixture.first().getFileId()).softDelete(NOW);
        }
        flushAndClear();

        assertDownloadsDenied(fixture, actorId);
    }

    @ParameterizedTest
    @EnumSource(value = AdditionalAttachmentUploadStatus.class, names = "COMPLETED", mode = EnumSource.Mode.EXCLUDE)
    void 미완료_추가_업로드_사진은_다운로드에서_제외한다(AdditionalAttachmentUploadStatus status) {
        Fixture fixture = fixture();
        share(fixture, TripSharePermission.READ);
        AdditionalAttachmentUploadBatch batch = new AdditionalAttachmentUploadBatch(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                fixture.trip().getId(), fixture.owner().getUserId(), 1, 1, true);
        ReflectionTestUtils.setField(batch, "status", status);
        em.persist(batch);
        AdditionalAttachmentUploadItem item = new AdditionalAttachmentUploadItem(
                batch, 1, "first.heic", "image/heic", 1L, "original-first");
        item.linkAttachment(fixture.first().getId());
        em.persist(item);
        flushAndClear();

        assertDownloadsDenied(fixture, fixture.reader().getUserId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "READ", "WRITE"})
    void 두_여행에_모두_접근할_수_있어도_사진을_섞으면_거절한다(String permission) {
        Fixture fixture = fixture();
        Long actorId = actor(fixture, permission);
        Trip otherTrip = trip(fixture.owner());
        TripAttachment otherPhoto = photo(otherTrip, "other");
        if (!permission.equals("OWNER")) {
            em.persist(new TripShareUser(otherTrip.getId(), fixture.owner().getUserId(), actorId,
                    TripSharePermission.valueOf(permission), NOW.plusHours(1), NOW));
        }
        flushAndClear();

        assertThatThrownBy(() -> bulkDownloads.issueDownloadUrl(actorId,
                List.of(fixture.first().getId(), otherPhoto.getId())))
                .isInstanceOf(InvalidAttachmentIdsException.class);
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "READ", "WRITE"})
    void 접근할_수_없는_다른_여행의_사진이_섞이면_존재를_숨긴다(String permission) {
        Fixture fixture = fixture();
        Long actorId = actor(fixture, permission);
        User otherOwner = new User("other-owner@shared.test", "다른 소유자");
        em.persist(otherOwner);
        TripAttachment otherPhoto = photo(trip(otherOwner), "other");
        flushAndClear();

        assertThatThrownBy(() -> bulkDownloads.issueDownloadUrl(actorId,
                List.of(fixture.first().getId(), otherPhoto.getId())))
                .isInstanceOf(AttachmentNotFoundException.class);
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "READ", "WRITE"})
    void 단건_다운로드는_여행과_장소를_추가_조회하지_않는다(String permission) throws Exception {
        Fixture fixture = fixture();
        Long actorId = actor(fixture, permission);
        attachPlace(fixture.trip(), fixture.first(), 1);
        flushAndClear();
        stubStorage();
        Statistics statistics = resetStatistics();

        assertThat(downloads.issueDownloadUrl(actorId, fixture.first().getId()).downloadUrl())
                .isEqualTo("https://download.test");

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(permission.equals("OWNER") ? 1 : 2);
        assertThat(statistics.getEntityStatistics(TripDetailPlace.class.getName()).getLoadCount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"OWNER,1", "OWNER,10", "OWNER,200", "READ,1", "READ,10", "READ,200",
            "WRITE,1", "WRITE,10", "WRITE,200"})
    void 일괄_다운로드는_사진_수와_무관하게_권한만_추가_조회한다(String permission, int count) throws Exception {
        Fixture fixture = fixture();
        Long actorId = actor(fixture, permission);
        List<Long> ids = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            TripAttachment photo = index == 1 ? fixture.first() : photo(fixture.trip(), "bulk-" + index);
            attachPlace(fixture.trip(), photo, index);
            ids.add(photo.getId());
        }
        flushAndClear();
        stubStorage();
        Statistics statistics = resetStatistics();

        assertThat(bulkDownloads.issueDownloadUrl(actorId, ids).downloadUrl()).isEqualTo("https://download.test");

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(permission.equals("OWNER") ? 1 : 2);
        assertThat(statistics.getEntityStatistics(TripDetailPlace.class.getName()).getLoadCount()).isZero();
    }

    private void attachPlace(Trip trip, TripAttachment photo, int order) {
        TripDetailPlace place = TripDetailPlace.fromAnalysis(trip.getId(), order, "장소" + order,
                BigDecimal.ZERO, BigDecimal.ZERO, NOW, NOW, "preview");
        em.persist(place);
        photo.classify(place.getId(), RegionOrigin.EXIF,
                NOW, BigDecimal.ZERO, BigDecimal.ZERO, 1);
    }

    private Statistics resetStatistics() {
        Statistics statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        return statistics;
    }

    private void assertDenied(Fixture fixture, Long actorId) {
        assertThatThrownBy(() -> stories.findStory(actorId, fixture.trip().getId()))
                .isInstanceOf(StoryNotFoundException.class);
        assertDownloadsDenied(fixture, actorId);
        Statistics statistics = resetStatistics();
        assertThatThrownBy(() -> tripService.findTripDetail(fixture.trip().getId(), actorId))
                .isInstanceOf(com.yeodam.yeodambe.common.exception.TripNotFoundException.class);
        assertThatThrownBy(() -> tripService.findTripDetailMap(fixture.trip().getId(), actorId))
                .isInstanceOf(com.yeodam.yeodambe.common.exception.TripNotFoundException.class);
        assertThat(invitedTrips.findInvitedTrips(actorId, null).items()).isEmpty();
        long countQueries = java.util.Arrays.stream(statistics.getQueries())
                .filter(query -> query.contains("TripAttachmentCount")
                        || query.contains("TripDetailMapMarker")
                        || query.contains("UnclassifiedFolderAttachmentCount"))
                .mapToLong(query -> statistics.getQueryStatistics(query).getExecutionCount())
                .sum();
        assertThat(countQueries).isZero();
        verifyNoInteractions(storage);
    }

    private void assertDownloadsDenied(Fixture fixture, Long actorId) {
        assertThatThrownBy(() -> downloads.issueDownloadUrl(actorId, fixture.first().getId()))
                .isInstanceOf(AttachmentNotFoundException.class);
        assertThatThrownBy(() -> bulkDownloads.issueDownloadUrl(actorId, fixture.photoIds()))
                .isInstanceOf(AttachmentNotFoundException.class);
        verifyNoInteractions(storage);
    }

    private Long actor(Fixture fixture, String permission) {
        if (permission.equals("OWNER")) {
            return fixture.owner().getUserId();
        }
        share(fixture, TripSharePermission.valueOf(permission));
        return fixture.reader().getUserId();
    }

    private TripShareUser share(Fixture fixture, TripSharePermission permission) {
        TripShareUser share = new TripShareUser(fixture.trip().getId(), fixture.owner().getUserId(),
                fixture.reader().getUserId(), permission, NOW.plusHours(1), NOW);
        em.persist(share);
        return share;
    }

    private Fixture fixture() {
        User owner = new User("owner@shared.test", "소유자");
        User reader = new User("reader@shared.test", "공유자");
        em.persist(owner);
        em.persist(reader);
        Trip trip = trip(owner);
        TripAttachment first = photo(trip, "first");
        TripAttachment second = photo(trip, "second");
        TripDetailPlace place = TripDetailPlace.fromAnalysis(trip.getId(), 1, "장소",
                BigDecimal.ZERO, BigDecimal.ZERO, NOW, NOW, "preview-first");
        em.persist(place);
        Story story = new Story(trip.getId(), Story.Mood.CALM, "공유 여행 요약");
        story.complete();
        em.persist(story);
        trip.changeCurrentStory(story.getId());
        var block = new StoryBlock(story.getId(), first.getId(), place.getId(),
                NOW.toLocalDate(), 1, "첫째 날", "사진 요약", null);
        em.persist(block);
        when(contents.findById(story.getId())).thenReturn(java.util.Optional.of(
                new StoryContentDocument(story.getId(), Story.Mood.CALM, "공유 여행 요약",
                        List.of(place.getId()), List.of(new StoryContentBlock(
                        block.getId(), first.getId(), place.getId(), NOW.toLocalDate(),
                        "첫째 날", "사진 요약", null, null, null, null)), block.getId() + 1)));

        return new Fixture(owner, reader, trip, first, second);
    }

    private Trip trip(User owner) {
        Trip trip = new Trip(owner.getUserId(), "여행", NOW.toLocalDate(), NOW.toLocalDate());
        ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
        em.persist(trip);
        return trip;
    }

    private TripAttachment photo(Trip trip, String name) {
        StoredFile file = StoredFile.uploaded(trip.getUserId(), name + ".heic", "original-" + name, "image/heic");
        em.persist(file);
        TripAttachment photo = TripAttachment.initial(trip.getId(), file.getId(), "analyze-" + name, "preview-" + name);
        em.persist(photo);
        return photo;
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private void stubStorage() throws Exception {
        when(storage.createReadUrl(anyString())).thenReturn("https://preview.test");
        when(storage.createDownloadUrl(anyString(), anyString())).thenReturn("https://download.test");
        when(storage.open(anyString())).thenAnswer(call -> new ByteArrayInputStream(new byte[]{1}));
        when(storage.storeDownloadArchive(any(Path.class))).thenReturn("archive");
    }

    private record Fixture(User owner, User reader, Trip trip, TripAttachment first, TripAttachment second) {
        List<Long> photoIds() {
            return List.of(first.getId(), second.getId());
        }
    }
}
