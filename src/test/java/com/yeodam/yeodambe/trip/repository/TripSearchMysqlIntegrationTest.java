package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.integration.service.AiQueryService;
import com.yeodam.yeodambe.integration.service.response.AiQueryParseResponse;
import com.yeodam.yeodambe.integration.service.response.AiQuerySearchResponse;
import com.yeodam.yeodambe.search.service.SearchService;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.service.*;
import com.yeodam.yeodambe.trip.service.request.TripSearchCondition;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({TripService.class, SearchService.class, tools.jackson.databind.ObjectMapper.class})
class TripSearchMysqlIntegrationTest extends TripSearchRepositoryTest {
    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:9.7.2");

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private TripService tripService;
    @Autowired
    private SearchService searchService;
    @MockitoBean
    private RegionCatalog regionCatalog;
    @MockitoBean
    private TripAccessService accessService;
    @MockitoBean
    private TripAttachmentStorageClient storage;
    @MockitoBean
    private AiQueryService ai;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 사진_개수가_늘어도_일괄_결과_조회_쿼리_수는_증가하지_않는다() {
        List<Long> ids = fixture(30);
        var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        assertThat(tripService.findSearchResults(ownerId, List.of(ids.getFirst())).attachments()).hasSize(1);
        long singleCount = stats.getPrepareStatementCount();
        stats.clear();
        assertThat(tripService.findSearchResults(ownerId, ids).attachments()).hasSize(30);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(singleCount).isEqualTo(2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void AI를_트랜잭션_밖에서_호출하고_근거_사진이_삭제되면_답변을_제외한다() {
        List<Long> ids = fixture(2);
        when(ai.parse("바다 사진")).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new AiQueryParseResponse(AiQueryParseResponse.Intent.QUESTION, null, null, null, "바다", true);
        });
        when(ai.search(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    entityManager.find(TripAttachment.class, ids.getLast()).softDelete(LocalDateTime.now()));
            return new AiQuerySearchResponse(List.of(
                    new AiQuerySearchResponse.Attachment(ids.getFirst(), .8),
                    new AiQuerySearchResponse.Attachment(ids.getLast(), .9)), "근거 답변", null);
        });
        var response = searchService.search(ownerId, "바다 사진");
        assertThat(response.attachments()).extracting(item -> item.tripAttachmentId())
                .containsExactly(ids.getFirst());
        assertThat(response.answer()).isNull();
        assertThat(response.folders().getFirst().attachmentCount()).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 후보_사진의_메타데이터를_한_쿼리로_조회한다() {
        fixture(30);
        var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        var candidates = tripService.findSearchCandidates(ownerId,
                new TripSearchCondition(null, null, null));

        assertThat(candidates).hasSize(30);
        assertThat(candidates.getFirst().analyzeStorageKey()).isEqualTo("analyze");
        assertThat(candidates.getFirst().placeName()).isEqualTo("장소");
        assertThat(candidates.getFirst().tripName()).isEqualTo("검색 여행");
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void AI_검색_중_장소가_삭제되면_근거_사진과_답변을_제외한다() {
        List<Long> ids = fixture(1);
        when(ai.parse("바다 사진")).thenReturn(new AiQueryParseResponse(
                AiQueryParseResponse.Intent.QUESTION, null, null, null, "바다", true));
        when(ai.search(any())).thenAnswer(invocation -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                TripAttachment photo = entityManager.find(TripAttachment.class, ids.getFirst());
                entityManager.createQuery("update TripDetailPlace p set p.deletedAt = :now where p.id = :id")
                        .setParameter("now", LocalDateTime.now()).setParameter("id", photo.getTripPlaceId())
                        .executeUpdate();
            });
            return new AiQuerySearchResponse(List.of(
                    new AiQuerySearchResponse.Attachment(ids.getFirst(), .8)), "근거 답변", null);
        });

        var response = searchService.search(ownerId, "바다 사진");

        assertThat(response.attachments()).isEmpty();
        assertThat(response.folders()).isEmpty();
        assertThat(response.answer()).isNull();
    }

    @Test
    void 같은_영속성_컨텍스트에서도_AI_호출_후_최신_장소명을_반환한다() {
        List<Long> ids = fixture(1);
        entityManager.clear();
        var candidates = tripService.findSearchCandidates(ownerId,
                new TripSearchCondition(null, null, null));
        assertThat(candidates.getFirst().placeName()).isEqualTo("장소");
        entityManager.createQuery("update TripDetailPlace p set p.placeName = :name where p.tripId = :tripId")
                .setParameter("name", "변경 장소")
                .setParameter("tripId", entityManager.createQuery("select a.tripId from TripAttachment a where a.id = :id", Long.class)
                        .setParameter("id", ids.getFirst()).getSingleResult())
                .executeUpdate();

        var result = tripService.findSearchResults(ownerId, ids);

        assertThat(result.folders().getFirst().placeName()).isEqualTo("변경 장소");
    }

    private Long ownerId;

    private List<Long> fixture(int count) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            User user = new User(java.util.UUID.randomUUID() + "@mysql.test", "검색");
            entityManager.persist(user);
            ownerId = user.getUserId();
            Trip trip = new Trip(ownerId, "검색 여행", LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 1));
            entityManager.persist(trip);
            entityManager.createQuery("update Trip t set t.processingStatus = :status where t.id = :id")
                    .setParameter("status", ProcessingStatus.COMPLETED).setParameter("id", trip.getId())
                    .executeUpdate();
            TripDetailPlace place = TripDetailPlace.fromAnalysis(trip.getId(), 1, "장소", BigDecimal.ZERO, BigDecimal.ZERO,
                    LocalDateTime.of(2024, 2, 29, 0, 0), LocalDateTime.of(2024, 3, 1, 0, 0), "place-preview");
            entityManager.persist(place);
            return LongStream.range(0, count).mapToObj(index -> {
                StoredFile file = StoredFile.uploaded(ownerId, "photo.jpg", "original-" + index, "image/jpeg");
                entityManager.persist(file);
                TripAttachment photo = TripAttachment.initial(trip.getId(), file.getId(), "analyze", "preview");
                photo.classify(place.getId(), RegionOrigin.EXIF, null, null, null, 80);
                entityManager.persist(photo);
                return photo.getId();
            }).toList();
        });
    }
}
