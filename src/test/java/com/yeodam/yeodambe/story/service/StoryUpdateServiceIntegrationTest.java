package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.AttachmentCannotBeReusedException;
import com.yeodam.yeodambe.common.exception.InvalidStoryUpdateException;
import com.yeodam.yeodambe.common.exception.StoryOrAttachmentNotFoundException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.story.service.request.StoryUpdateRequest;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.service.TripAccessService;
import com.yeodam.yeodambe.trip.service.TripEditAuthorizationService;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, StoryUpdateService.class, StoryReadService.class,
        StoryValidator.class, TripAccessService.class})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StoryUpdateServiceIntegrationTest {
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired StoryUpdateService service;
    @MockitoBean TripAttachmentStorageClient storage;
    @MockitoBean TripEditAuthorizationService authorizationService;
    private TransactionTemplate tx;
    private Fixture fixture;

    record Fixture(Long userId, Long tripId, Long storyId, Long blockId, Long photoId, Long placeId) {}

    @BeforeEach
    void prepare() {
        tx = new TransactionTemplate(transactionManager);
        fixture = tx.execute(status -> {
            User user = new User(UUID.randomUUID() + "@story-update.test", "수정회원");
            em.persist(user);
            Trip trip = new Trip(user.getUserId(), "여행", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5));
            ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
            em.persist(trip);
            TripDetailPlace place = place(trip.getId(), 2);
            TripAttachment photo = photo(user.getUserId(), trip.getId(), place);
            Story story = new Story(trip.getId(), Story.Mood.PLAIN, "기존 요약");
            story.complete();
            em.persist(story);
            StoryBlock block = new StoryBlock(story.getId(), photo.getId(), place.getId(),
                    place.getStartedAt().toLocalDate(), 1, "기존 날짜", "기존 설명", "기존 메모");
            em.persist(block);
            trip.changeCurrentStory(story.getId());
            return new Fixture(user.getUserId(), trip.getId(), story.getId(), block.getId(), photo.getId(), place.getId());
        });
        when(storage.createReadUrl(anyString())).thenAnswer(invocation -> "https://image.test/" + invocation.getArgument(0));
    }

    @Test
    void 전체_내용을_수정하되_같은사진의_기존_날짜장소를_유지한다() {
        tx.executeWithoutResult(status -> {
            TripDetailPlace changed = place(fixture.tripId(), 3);
            em.find(TripAttachment.class, fixture.photoId()).classify(changed.getId(), RegionOrigin.EXIF,
                    changed.getStartedAt(), BigDecimal.ZERO, BigDecimal.ZERO, 1);
        });
        var response = service.update(fixture.userId(), fixture.storyId(), request(
                block(fixture.blockId(), fixture.photoId(), 1)));
        assertThat(response.storyId()).isEqualTo(fixture.storyId());
        assertThat(response.tone()).isEqualTo(Story.Mood.CALM);
        assertThat(response.storySummary()).isEqualTo("수정 요약");
        assertThat(response.days().getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 2));
        var saved = response.days().getFirst().blocks().getFirst();
        assertThat(saved.tripPlaceId()).isEqualTo(fixture.placeId());
        assertThat(saved.detailSummary()).isEqualTo("요청 설명");
        assertThat(saved.memo()).isEqualTo("요청 메모");
    }

    @Test
    void 신규블록_추가와_누락삭제_재전송_삭제블록_복구를_처리한다() {
        Long newPhoto = newPhoto(true);
        var input = request(block(null, newPhoto, 1));
        var first = service.update(fixture.userId(), fixture.storyId(), input);
        Long newBlock = first.days().getFirst().blocks().getFirst().storyBlockId();
        var repeated = service.update(fixture.userId(), fixture.storyId(), input);
        assertThat(repeated.days().getFirst().blocks().getFirst().storyBlockId()).isEqualTo(newBlock);
        tx.executeWithoutResult(status -> {
            assertThat(em.find(StoryBlock.class, fixture.blockId()).getDeletedAt()).isNotNull();
            assertThat(em.find(TripAttachment.class, fixture.photoId()).getDeletedAt()).isNull();
            assertThat(em.createQuery("select count(b) from StoryBlock b where b.storyId = :id", Long.class)
                    .setParameter("id", fixture.storyId()).getSingleResult()).isEqualTo(2L);
        });
        var restored = service.update(fixture.userId(), fixture.storyId(), request(
                block(null, fixture.photoId(), 1), block(newBlock, newPhoto, 2)));
        assertThat(restored.days().stream().flatMap(day -> day.blocks().stream()))
                .extracting(item -> item.storyBlockId()).containsExactly(fixture.blockId(), newBlock);
    }

    @Test
    void 사진교체후_새사진과_현재장소날짜로_응답하며_원본사진은_유지한다() {
        Long replacement = newPhoto(true);
        var response = service.update(fixture.userId(), fixture.storyId(), request(
                block(fixture.blockId(), replacement, 1)));
        assertThat(response.days().getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 4));
        var saved = response.days().getFirst().blocks().getFirst();
        assertThat(saved.storyBlockId()).isEqualTo(fixture.blockId());
        assertThat(saved.tripAttachmentId()).isEqualTo(replacement);
        tx.executeWithoutResult(status -> assertThat(em.find(TripAttachment.class, fixture.photoId()).getDeletedAt()).isNull());
    }

    @Test
    void 앞선_신규블록을_저장했어도_뒤의_장소검증_실패시_전체롤백한다() {
        Long good = newPhoto(true);
        Long bad = newPhoto(false);
        assertThatThrownBy(() -> service.update(fixture.userId(), fixture.storyId(), request(
                block(null, good, 1), block(null, bad, 2))))
                .isInstanceOf(InvalidStoryUpdateException.class);
        tx.executeWithoutResult(status -> {
            assertThat(em.find(Story.class, fixture.storyId()).getStorySummary()).isEqualTo("기존 요약");
            assertThat(em.find(StoryBlock.class, fixture.blockId()).getDeletedAt()).isNull();
            assertThat(em.createQuery("select count(b) from StoryBlock b where b.storyId = :id", Long.class)
                    .setParameter("id", fixture.storyId()).getSingleResult()).isEqualTo(1L);
        });
    }

    @Test
    void 타인과_없는블록과_타여행사진과_중복사진을_거절한다() {
        var valid = request(block(fixture.blockId(), fixture.photoId(), 1));
        assertThatThrownBy(() -> service.update(-1L, fixture.storyId(), valid))
                .isInstanceOf(StoryOrAttachmentNotFoundException.class);
        assertThatThrownBy(() -> service.update(fixture.userId(), fixture.storyId(), request(block(-1L, fixture.photoId(), 1))))
                .isInstanceOf(StoryOrAttachmentNotFoundException.class);
        Long otherPhoto = tx.execute(status -> {
            Trip other = new Trip(fixture.userId(), "다른 여행", LocalDate.now(), LocalDate.now());
            em.persist(other);
            return photo(fixture.userId(), other.getId(), place(other.getId(), 3)).getId();
        });
        assertThatThrownBy(() -> service.update(fixture.userId(), fixture.storyId(), request(block(null, otherPhoto, 1))))
                .isInstanceOf(StoryOrAttachmentNotFoundException.class);
        assertThatThrownBy(() -> service.update(fixture.userId(), fixture.storyId(), request(
                block(fixture.blockId(), fixture.photoId(), 1), block(null, fixture.photoId(), 2))))
                .isInstanceOf(AttachmentCannotBeReusedException.class);
    }

    @Test
    void 삭제사진과_다른블록이_차지한_사진을_거절한다() {
        Long additional = newPhoto(true);
        Long additionalBlock = service.update(fixture.userId(), fixture.storyId(), request(
                block(fixture.blockId(), fixture.photoId(), 1), block(null, additional, 2)))
                .days().getLast().blocks().getFirst().storyBlockId();
        assertThat(additionalBlock).isNotEqualTo(fixture.blockId());
        assertThatThrownBy(() -> service.update(fixture.userId(), fixture.storyId(), request(
                block(fixture.blockId(), additional, 1))))
                .isInstanceOf(AttachmentCannotBeReusedException.class);
        tx.executeWithoutResult(status -> em.find(TripAttachment.class, additional).softDelete(LocalDateTime.now()));
        assertThatThrownBy(() -> service.update(fixture.userId(), fixture.storyId(), request(block(additionalBlock, additional, 1))))
                .isInstanceOf(StoryOrAttachmentNotFoundException.class);
    }

    private StoryUpdateRequest request(StoryUpdateRequest.Block... blocks) {
        return new StoryUpdateRequest(Story.Mood.CALM, "수정 요약", List.of(blocks));
    }

    private StoryUpdateRequest.Block block(Long id, Long photoId, int order) {
        return new StoryUpdateRequest.Block(id, photoId, "요청 날짜", "요청 설명", "요청 메모", order);
    }

    private Long newPhoto(boolean withPlace) {
        return tx.execute(status -> photo(fixture.userId(), fixture.tripId(),
                withPlace ? place(fixture.tripId(), 4) : null).getId());
    }

    private TripDetailPlace place(Long tripId, int day) {
        var place = TripDetailPlace.fromAnalysis(tripId, day, "장소" + day, BigDecimal.ZERO,
                BigDecimal.ZERO, LocalDateTime.of(2026, 10, day, 12, 0),
                LocalDateTime.of(2026, 10, day, 13, 0), null);
        em.persist(place);
        return place;
    }

    private TripAttachment photo(Long userId, Long tripId, TripDetailPlace place) {
        String key = UUID.randomUUID().toString();
        StoredFile file = StoredFile.uploaded(userId, "사진.jpg", key, "image/jpeg");
        em.persist(file);
        TripAttachment photo = TripAttachment.initial(tripId, file.getId(), key + "-analyze", key + "-preview");
        if (place != null) {
            photo.classify(place.getId(), RegionOrigin.EXIF, place.getStartedAt(), BigDecimal.ZERO, BigDecimal.ZERO, 1);
        }
        em.persist(photo);
        return photo;
    }
}
