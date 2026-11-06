package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.story.service.response.StoryContentBlockDetail;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, StoryBlockReferenceQuery.class, org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class})
@ActiveProfiles("test")
class StoryBlockReferenceQueryIntegrationTest {
    @Autowired
    private EntityManager entityManager;

    @Autowired
    private StoryBlockReferenceQuery query;

    @Test
    void 블록배열순서와_사진의_현재장소가_아닌_원본장소를_유지한다() {
        Trip trip = trip();
        TripDetailPlace firstPlace = place(trip.getId(), 1);
        TripDetailPlace secondPlace = place(trip.getId(), 2);
        TripAttachment photo = photo(trip, "preview/current");
        List<StoryContentBlock> blocks = List.of(
                block(91L, photo.getId(), secondPlace.getId()),
                block(10L, photo.getId(), firstPlace.getId())
        );
        entityManager.flush();
        entityManager.clear();

        List<StoryContentBlockDetail> result = query.findByBlocks(blocks);

        assertThat(result).extracting(detail -> detail.block().id()).containsExactly(91L, 10L);
        assertThat(result).extracting(StoryContentBlockDetail::placeId)
                .containsExactly(secondPlace.getId(), firstPlace.getId());
        assertThat(result).extracting(StoryContentBlockDetail::currentPreviewStorageKey)
                .containsExactly("preview/current", "preview/current");
    }

    @Test
    void 누락된_첨부와_장소도_입력블록에_대응하는_null행으로_남긴다() {
        StoryContentBlock block = block(1L, Long.MAX_VALUE, Long.MAX_VALUE);

        List<StoryContentBlockDetail> result = query.findByBlocks(List.of(block));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().attachmentId()).isNull();
        assertThat(result.getFirst().fileId()).isNull();
        assertThat(result.getFirst().placeId()).isNull();
        assertThat(result.getFirst().block()).isSameAs(block);
    }

    @Test
    void 삭제된_사진과_원본장소도_필터링하지_않고_검증자료로_반환한다() {
        Trip trip = trip();
        TripAttachment photo = photo(trip, "preview/deleted");
        TripDetailPlace place = place(trip.getId(), 1);
        photo.softDelete(LocalDateTime.of(2026, 11, 6, 12, 0));
        entityManager.flush();
        entityManager.clear();

        StoryContentBlockDetail result = query.findByBlocks(
                List.of(block(1L, photo.getId(), place.getId()))).getFirst();

        assertThat(result.attachmentDeletedAt()).isNotNull();
        assertThat(result.attachmentTripId()).isEqualTo(trip.getId());
        assertThat(result.fileId()).isNotNull();
        assertThat(result.placeId()).isEqualTo(place.getId());
    }

    @Test
    void 빈블록에는_SQL을_실행하지_않는다() {
        assertThat(query.findByBlocks(List.of())).isEmpty();
    }

    private Trip trip() {
        User user = new User(UUID.randomUUID() + "@reference.test", "검증");
        entityManager.persist(user);
        Trip trip = new Trip(user.getUserId(), "여행", LocalDate.now(), LocalDate.now());
        entityManager.persist(trip);
        return trip;
    }

    private TripDetailPlace place(Long tripId, int order) {
        LocalDateTime startedAt = LocalDateTime.of(2026, 11, order, 12, 0);
        TripDetailPlace place = TripDetailPlace.fromAnalysis(
                tripId, order, "장소", BigDecimal.ZERO, BigDecimal.ZERO, startedAt, startedAt, null
        );
        entityManager.persist(place);
        return place;
    }

    private TripAttachment photo(Trip trip, String key) {
        StoredFile file = StoredFile.uploaded(trip.getUserId(), "사진.jpg", UUID.randomUUID().toString(), "image/jpeg");
        entityManager.persist(file);
        TripAttachment photo = TripAttachment.initial(trip.getId(), file.getId(), "analysis", key);
        entityManager.persist(photo);
        return photo;
    }

    private StoryContentBlock block(Long id, Long attachmentId, Long placeId) {
        return new StoryContentBlock(
                id, attachmentId, placeId, LocalDate.of(2026, 11, 1), "날", "설명", null,
                null, null, null
        );
    }
}
