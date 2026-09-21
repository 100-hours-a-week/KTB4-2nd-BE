package com.yeodam.yeodambe.trip;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripRegion;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class TripMapRepositoryTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private TripRegionRepository tripRegionRepository;
    @Autowired
    private StoredFileRepository storedFileRepository;
    @Autowired
    private TripAttachmentRepository tripAttachmentRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void 본인의_완료된_미삭제_여행_지역만_조회한다() {
        User owner = userRepository.save(new User("owner@yeodam.test", "소유자"));
        User other = userRepository.save(new User("other@yeodam.test", "다른회원"));

        Trip visible = saveTrip(owner.getUserId(), "표시 여행", ProcessingStatus.COMPLETED, null);
        Trip processing = saveTrip(owner.getUserId(), "처리 여행", ProcessingStatus.PROCESSING, null);
        Trip deleted = saveTrip(
                owner.getUserId(),
                "삭제 여행",
                ProcessingStatus.COMPLETED,
                LocalDateTime.now()
        );
        Trip others = saveTrip(other.getUserId(), "타인 여행", ProcessingStatus.COMPLETED, null);

        tripRegionRepository.saveAll(List.of(
                region(visible, "50110", null),
                region(processing, "26110", null),
                region(deleted, "11110", null),
                region(others, "41110", null),
                region(visible, "50130", LocalDateTime.now())
        ));
        flushAndClear();

        List<TripRegion> result = tripRegionRepository.findAllForMap(
                owner.getUserId(),
                ProcessingStatus.COMPLETED
        );

        assertThat(result)
                .extracting(TripRegion::getRegionCode)
                .containsExactly("50110");
        assertThat(result.getFirst().getTrip().getId()).isEqualTo(visible.getId());
    }

    @Test
    void 여행별_미삭제_첨부_개수를_집계한다() {
        User owner = userRepository.save(new User("files@yeodam.test", "사진회원"));
        Trip trip = saveTrip(owner.getUserId(), "사진 여행", ProcessingStatus.COMPLETED, null);

        TripAttachment first = attachment(owner.getUserId(), trip.getId(), "first");
        TripAttachment second = attachment(owner.getUserId(), trip.getId(), "second");
        TripAttachment deleted = attachment(owner.getUserId(), trip.getId(), "deleted");
        ReflectionTestUtils.setField(deleted, "deletedAt", LocalDateTime.now());
        tripAttachmentRepository.saveAll(List.of(first, second, deleted));
        flushAndClear();

        var result = tripAttachmentRepository.countNotDeletedByTripIds(List.of(trip.getId()));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().tripId()).isEqualTo(trip.getId());
        assertThat(result.getFirst().attachmentCount()).isEqualTo(2L);
    }

    private Trip saveTrip(
            Long userId,
            String name,
            ProcessingStatus status,
            LocalDateTime deletedAt
    ) {
        Trip trip = new Trip(userId, name, LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "processingStatus", status);
        ReflectionTestUtils.setField(trip, "deletedAt", deletedAt);
        return tripRepository.save(trip);
    }

    private TripRegion region(Trip trip, String code, LocalDateTime deletedAt) {
        TripRegion region = new TripRegion(
                trip,
                code,
                "테스트 지역 " + code,
                new BigDecimal("35.00000000"),
                new BigDecimal("127.00000000")
        );
        ReflectionTestUtils.setField(region, "deletedAt", deletedAt);
        return region;
    }

    private TripAttachment attachment(Long userId, Long tripId, String key) {
        StoredFile file = storedFileRepository.save(StoredFile.uploaded(
                userId,
                key + ".jpg",
                "original/" + key,
                "image/jpeg"
        ));
        return TripAttachment.initial(
                tripId,
                file.getId(),
                "analyze/" + key,
                "preview/" + key
        );
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
