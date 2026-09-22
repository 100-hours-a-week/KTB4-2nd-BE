package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.RegionOrigin;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TripAttachmentRepositoryTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private TripDetailPlaceRepository tripDetailPlaceRepository;
    @Autowired
    private StoredFileRepository storedFileRepository;
    @Autowired
    private TripAttachmentRepository tripAttachmentRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void 장소_폴더의_활성_첨부를_커서_다음부터_최신순으로_조회한다() {
        User user = userRepository.save(new User("attachment-list@yeodam.test", "사진목록"));
        Trip trip = tripRepository.save(new Trip(
                user.getUserId(), "첨부 목록", LocalDate.now(), LocalDate.now()
        ));
        TripDetailPlace place = tripDetailPlaceRepository.save(TripDetailPlace.fromAnalysis(
                trip.getId(),
                1,
                new BigDecimal("33.50000000"),
                new BigDecimal("126.50000000"),
                LocalDateTime.of(2026, 9, 22, 10, 0),
                LocalDateTime.of(2026, 9, 22, 11, 0),
                "preview"
        ));
        StoredFile file = storedFileRepository.save(StoredFile.uploaded(
                user.getUserId(), "photo.jpg", "original", "image/jpeg"
        ));

        List<TripAttachment> savedAttachments = new ArrayList<>();
        for (int index = 0; index < 19; index++) {
            TripAttachment attachment = TripAttachment.initial(
                    trip.getId(), file.getId(), "analyze-" + index, "preview-" + index
            );
            attachment.classify(
                    place.getId(), RegionOrigin.EXIF, null, null, null, 80
            );
            savedAttachments.add(tripAttachmentRepository.save(attachment));
        }
        entityManager.flush();

        LocalDateTime latest = LocalDateTime.of(2026, 9, 22, 12, 0);
        for (int index = 0; index < savedAttachments.size(); index++) {
            entityManager.createNativeQuery("""
                    update trip_attachments
                    set created_at = :createdAt
                    where trip_attachment_id = :tripAttachmentId
                    """)
                    .setParameter("createdAt", latest.minusMinutes(index))
                    .setParameter("tripAttachmentId", savedAttachments.get(index).getId())
                    .executeUpdate();
        }
        entityManager.clear();

        List<TripAttachment> firstPage = tripAttachmentRepository.findByPlaceFolderWithCursor(
                trip.getId(), place.getId(), ClassificationStatus.ACTIVE,
                null, null, PageRequest.of(0, 19)
        );

        assertThat(firstPage).hasSize(19);
        assertThat(firstPage)
                .extracting(TripAttachment::getId)
                .containsExactlyElementsOf(savedAttachments.stream()
                        .map(TripAttachment::getId)
                        .toList());

        TripAttachment lastReturnedAttachment = firstPage.get(17);
        List<TripAttachment> secondPage = tripAttachmentRepository.findByPlaceFolderWithCursor(
                trip.getId(), place.getId(), ClassificationStatus.ACTIVE,
                lastReturnedAttachment.getCreatedAt(),
                lastReturnedAttachment.getId(),
                PageRequest.of(0, 19)
        );

        assertThat(secondPage)
                .extracting(TripAttachment::getId)
                .containsExactly(savedAttachments.get(18).getId());
    }

    @Test
    void 활성_여행의_소유자만_원본_첨부를_조회한다() {
        User owner = userRepository.save(new User("attachment-owner@yeodam.test", "소유자"));
        User other = userRepository.save(new User("attachment-other@yeodam.test", "다른사용자"));
        Trip trip = tripRepository.save(new Trip(
                owner.getUserId(), "원본 조회", LocalDate.now(), LocalDate.now()
        ));
        StoredFile file = storedFileRepository.save(StoredFile.uploaded(
                owner.getUserId(), "original.jpg", "trip-uploads/original", "image/jpeg"
        ));
        TripAttachment attachment = tripAttachmentRepository.save(
                TripAttachment.initial(
                        trip.getId(), file.getId(), "analyze", "preview"
                )
        );
        entityManager.flush();
        entityManager.clear();

        assertThat(tripAttachmentRepository.findAccessibleById(
                attachment.getId(), owner.getUserId()
        )).isPresent();
        assertThat(tripAttachmentRepository.findAccessibleById(
                attachment.getId(), other.getUserId()
        )).isEmpty();
    }
}
