package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.SharedTestMysql;
import com.yeodam.yeodambe.common.exception.StoryNotFoundException;
import com.yeodam.yeodambe.common.exception.StoryOrAttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.StoryTonePreviewTakenAtMissingException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.integration.service.RunpodStatusService;
import com.yeodam.yeodambe.integration.service.response.RunpodStatusResponse;
import com.yeodam.yeodambe.story.client.StoryTonePreviewAiClient;
import com.yeodam.yeodambe.story.client.request.StoryTonePreviewAiRequest;
import com.yeodam.yeodambe.story.client.response.StoryTonePreviewAiResponse;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.story.service.request.StoryTonePreviewRequest;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StoryTonePreviewMysqlIntegrationTest.MysqlConfig.class,
        StoryTonePreviewInputService.class, StoryTonePreviewRequestValidator.class,
        StoryValidator.class, StoryTonePreviewService.class, StoryTonePreviewResultValidator.class,
        org.springframework.validation.beanvalidation.LocalValidatorFactoryBean.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StoryTonePreviewMysqlIntegrationTest {
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired StoryTonePreviewInputService input;
    @Autowired StoryTonePreviewService service;
    @MockitoBean RunpodStatusService ready;
    @MockitoBean StoryTonePreviewAiClient ai;
    @MockitoBean TripAttachmentStorageClient storage;
    private TransactionTemplate tx;
    private Fixture fixture;

    record Fixture(Long userId, Long tripId, Long storyId, Long blockId, Long photoId, Long placeId) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class MysqlConfig {
        @Bean
        JdbcConnectionDetails mysql() throws SQLException {
            return SharedTestMysql.createDatabase();
        }
    }

    @BeforeEach
    void prepare() {
        tx = new TransactionTemplate(transactionManager);
        fixture = tx.execute(status -> {
            var user = new User(UUID.randomUUID() + "@preview.test", "미리보기회원");
            em.persist(user);
            var trip = new Trip(user.getUserId(), "여행", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5));
            ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
            em.persist(trip);
            var photo = photo(user.getUserId(), trip.getId());
            var story = new Story(trip.getId(), Story.Mood.PLAIN, "기존 요약");
            story.complete();
            em.persist(story);
            var block = new StoryBlock(story.getId(), photo.getId(), photo.getTripPlaceId(),
                    LocalDate.of(2026, 10, 1), 1, "기존 라벨", "기존 캡션", null);
            em.persist(block);
            trip.changeCurrentStory(story.getId());
            return new Fixture(user.getUserId(), trip.getId(), story.getId(), block.getId(), photo.getId(), photo.getTripPlaceId());
        });
    }

    @Test
    void 실제조회후_AI와_URL발급은_트랜잭션밖에서_실행하고_DB내용은_유지한다() {
        when(ready.getStatus()).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new RunpodStatusResponse(true, true);
        });
        when(ai.preview(eq(fixture.tripId()), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            StoryTonePreviewAiRequest request = invocation.getArgument(1);
            assertThat(request.blocks().getFirst().tripAttachmentId()).isEqualTo(fixture.photoId());
            return new StoryTonePreviewAiResponse("새 요약", List.of(
                    new StoryTonePreviewAiResponse.Block(1, fixture.photoId(), "기존 라벨", "새 캡션", null)));
        });
        when(storage.createReadUrl(anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return "https://image.test/preview";
        });

        var result = service.preview(fixture.userId(), fixture.storyId(), request());

        assertThat(result.storySummary()).isEqualTo("새 요약");
        assertThat(result.days().getFirst().blocks().getFirst().storyBlockId()).isEqualTo(fixture.blockId());
        tx.executeWithoutResult(status -> {
            var story = em.find(Story.class, fixture.storyId());
            assertThat(story.getMood()).isEqualTo(Story.Mood.PLAIN);
            assertThat(story.getStorySummary()).isEqualTo("기존 요약");
            assertThat(em.find(StoryBlock.class, fixture.blockId()).getDetailSummary()).isEqualTo("기존 캡션");
            assertThat(em.find(Trip.class, fixture.tripId()).getCurrentStoryId()).isEqualTo(fixture.storyId());
            assertThat(em.createQuery("select count(b) from StoryBlock b where b.storyId=:id", Long.class)
                    .setParameter("id", fixture.storyId()).getSingleResult()).isEqualTo(1L);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "history", "story-deleted", "trip-deleted", "story-processing", "trip-processing"})
    void 소유권과_현재연결과_삭제상태와_완료조건을_실제쿼리로_검사한다(String condition) {
        tx.executeWithoutResult(status -> {
            var story = em.find(Story.class, fixture.storyId());
            var trip = em.find(Trip.class, fixture.tripId());
            switch (condition) {
                case "history" -> {
                    var current = new Story(trip.getId(), Story.Mood.PLAIN, "다른 스토리");
                    current.complete();
                    em.persist(current);
                    trip.changeCurrentStory(current.getId());
                }
                case "story-deleted" -> story.softDelete(LocalDateTime.now());
                case "trip-deleted" -> trip.softDelete(LocalDateTime.now());
                case "story-processing" -> ReflectionTestUtils.setField(story, "processingStatus", Story.Status.PROCESSING);
                case "trip-processing" -> em.createQuery(
                        "update Trip t set t.processingStatus=:state where t.id=:id")
                        .setParameter("state", ProcessingStatus.PROCESSING)
                        .setParameter("id", fixture.tripId()).executeUpdate();
            }
        });
        if (condition.equals("trip-processing")) {
            tx.executeWithoutResult(status -> assertThat(em.find(Trip.class, fixture.tripId()).getProcessingStatus())
                    .isEqualTo(ProcessingStatus.PROCESSING));
        }
        Long userId = condition.equals("owner") ? -1L : fixture.userId();
        assertThatThrownBy(() -> service.preview(userId, fixture.storyId(), request()))
                .isInstanceOf(StoryNotFoundException.class);
        verifyNoInteractions(ready, ai, storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"photo", "file", "place", "unclassified", "taken-at"})
    void 삭제된사진파일장소와_미분류와_촬영시각누락을_AI호출전에_거절한다(String condition) {
        tx.executeWithoutResult(status -> {
            var photo = em.find(TripAttachment.class, fixture.photoId());
            switch (condition) {
                case "photo" -> photo.softDelete(LocalDateTime.now());
                case "file" -> em.find(StoredFile.class, photo.getFileId()).softDelete(LocalDateTime.now());
                case "place" -> em.find(TripDetailPlace.class, fixture.placeId()).softDelete(LocalDateTime.now());
                case "unclassified" -> ReflectionTestUtils.setField(photo, "classificationStatus", ClassificationStatus.UNCLASSIFIED);
                case "taken-at" -> ReflectionTestUtils.setField(photo, "takenAt", null);
            }
        });
        if (condition.equals("place")) {
            tx.executeWithoutResult(status -> assertThat(em.find(TripDetailPlace.class, fixture.placeId()).getDeletedAt())
                    .isNotNull());
        }
        Class<? extends RuntimeException> expected = condition.equals("taken-at")
                ? StoryTonePreviewTakenAtMissingException.class : StoryOrAttachmentNotFoundException.class;
        assertThatThrownBy(() -> service.preview(fixture.userId(), fixture.storyId(), request()))
                .isInstanceOf(expected);
        verifyNoInteractions(ready, ai, storage);
    }

    @Test
    void 같은여행의_신규사진을_준비하되_스토리블록을_생성하지않는다() {
        Long photoId = tx.execute(status -> photo(fixture.userId(), fixture.tripId()).getId());
        var request = new StoryTonePreviewRequest(Story.Mood.LITERARY,
                List.of(new StoryTonePreviewRequest.Block(null, photoId, 1, null, null, null)));
        var prepared = input.prepare(fixture.userId(), fixture.storyId(), request);
        assertThat(prepared.sources().getFirst().storyBlockId()).isNull();
        assertThat(prepared.sources().getFirst().tripAttachmentId()).isEqualTo(photoId);
        tx.executeWithoutResult(status -> assertThat(em.createQuery(
                "select count(b) from StoryBlock b where b.storyId=:id", Long.class)
                .setParameter("id", fixture.storyId()).getSingleResult()).isEqualTo(1L));
    }

    private StoryTonePreviewRequest request() {
        return new StoryTonePreviewRequest(Story.Mood.HUMOROUS, List.of(
                new StoryTonePreviewRequest.Block(fixture.blockId(), fixture.photoId(), 1, "기존 라벨", "편집 캡션", null)));
    }

    private TripAttachment photo(Long userId, Long tripId) {
        var place = TripDetailPlace.fromAnalysis(tripId, 1, "장소", BigDecimal.ZERO, BigDecimal.ZERO,
                LocalDateTime.of(2026, 10, 2, 12, 0), LocalDateTime.of(2026, 10, 2, 13, 0), null);
        em.persist(place);
        String key = UUID.randomUUID().toString();
        var file = StoredFile.uploaded(userId, "사진.jpg", key, "image/jpeg");
        em.persist(file);
        var photo = TripAttachment.initial(tripId, file.getId(), key + "-analyze", key + "-preview");
        photo.classify(place.getId(), RegionOrigin.EXIF, place.getStartedAt(), BigDecimal.ZERO, BigDecimal.ZERO, 1);
        em.persist(photo);
        return photo;
    }
}
