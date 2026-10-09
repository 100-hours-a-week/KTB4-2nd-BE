package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.integration.service.AiQueryService;
import com.yeodam.yeodambe.integration.service.response.AiQueryParseResponse;
import com.yeodam.yeodambe.integration.service.response.AiQuerySearchResponse;
import com.yeodam.yeodambe.search.service.SearchService;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.service.*;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

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
        assertThat(stats.getPrepareStatementCount()).isEqualTo(singleCount).isEqualTo(3);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void AI를_트랜잭션_밖에서_호출하고_근거_사진이_삭제되면_답변을_제외한다() {
        List<Long> ids = fixture(2);
        when(ai.parse("바다 사진")).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new AiQueryParseResponse(AiQueryParseResponse.Intent.QUESTION, null, null, null, "바다");
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
            return LongStream.range(0, count).mapToObj(index -> {
                StoredFile file = StoredFile.uploaded(ownerId, "photo.jpg", "original-" + index, "image/jpeg");
                entityManager.persist(file);
                TripAttachment photo = TripAttachment.initial(trip.getId(), file.getId(), "analyze", "preview");
                photo.classify(null, RegionOrigin.UNKNOWN, null, null, null, 80);
                entityManager.persist(photo);
                return photo.getId();
            }).toList();
        });
    }
}
