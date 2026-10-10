package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.AttachmentIssue;
import com.yeodam.yeodambe.trip.entity.RegionOrigin;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class StoryGenerationAttachmentRepositoryTest {
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripDetailPlaceRepository folders;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private StoredFileRepository files;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User owner;
    private Trip trip;
    private TripDetailPlace selected;
    private final LocalDateTime takenAt = LocalDateTime.of(2026, 10, 10, 12, 0);

    @BeforeEach
    void prepare() {
        owner = users.save(new User(UUID.randomUUID() + "@yeodam.test", "스토리회원"));
        trip = trip("스토리여행");
        selected = folder(trip, 1);
    }

    @Test
    void 선택폴더의_촬영시각있는_ACTIVE_사진만_조회한다() {
        TripAttachment expected = photo(trip, selected, takenAt);
        TripAttachment deleted = photo(trip, selected, takenAt);
        deleted.softDelete(takenAt);
        TripAttachment deletedFile = photo(trip, selected, takenAt);
        files.findById(deletedFile.getFileId()).orElseThrow().softDelete(takenAt);
        photo(trip, selected, null);
        TripAttachment unclassified = photo(trip, selected, takenAt);
        unclassified.unclassify(selected.getId(), AttachmentIssue.BLURRY, RegionOrigin.EXIF,
                takenAt, BigDecimal.ZERO, BigDecimal.ZERO, 80);
        TripAttachment discarded = photo(trip, selected, takenAt);
        TripAttachment removed = photo(trip, selected, takenAt);
        TripDetailPlace unselected = folder(trip, 2);
        photo(trip, unselected, takenAt);
        Trip otherTrip = trip("다른여행");
        TripDetailPlace otherFolder = folder(otherTrip, 1);
        photo(otherTrip, otherFolder, takenAt);
        photo(trip, otherFolder, takenAt);
        entityManager.flush();
        jdbcTemplate.update("UPDATE trip_attachments SET classification_status = 'DISCARDED' "
                + "WHERE trip_attachment_id = ?", discarded.getId());
        jdbcTemplate.update("UPDATE trip_attachments SET classification_status = 'DELETED' "
                + "WHERE trip_attachment_id = ?", removed.getId());
        entityManager.clear();

        var found = attachments.findForStoryGeneration(trip.getId(),
                List.of(selected.getId(), otherFolder.getId()));

        assertThat(found).extracting(TripAttachment::getId).containsExactly(expected.getId());
        assertThat(found.getFirst().getAnalyzeStorageKey()).isEqualTo(expected.getAnalyzeStorageKey());
        assertThat(found.getFirst().getTakenAt()).isEqualTo(takenAt);
        assertThat(found.getFirst().getEvaluation()).isEqualTo(80);
    }

    @Test
    void 폴더_ID_촬영시각_첨부_ID_순으로_조회한다() {
        TripDetailPlace second = folder(trip, 2);
        TripAttachment secondFolderPhoto = photo(trip, second, takenAt.minusDays(1));
        TripAttachment later = photo(trip, selected, takenAt.plusHours(1));
        TripAttachment firstAtSameTime = photo(trip, selected, takenAt);
        TripAttachment secondAtSameTime = photo(trip, selected, takenAt);
        entityManager.flush();
        entityManager.clear();

        assertThat(attachments.findForStoryGeneration(trip.getId(),
                List.of(second.getId(), selected.getId())))
                .extracting(TripAttachment::getId)
                .containsExactly(firstAtSameTime.getId(), secondAtSameTime.getId(),
                        later.getId(), secondFolderPhoto.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"trip", "folder"})
    void 여행이나_폴더가_삭제되면_사진을_반환하지_않는다(String target) {
        photo(trip, selected, takenAt);
        entityManager.flush();
        if (target.equals("trip")) {
            jdbcTemplate.update("UPDATE trips SET deleted_at = ? WHERE trip_id = ?", takenAt, trip.getId());
        } else {
            jdbcTemplate.update("UPDATE trip_detail_places SET deleted_at = ? WHERE trip_place_id = ?",
                    takenAt, selected.getId());
        }
        entityManager.clear();

        assertThat(attachments.findForStoryGeneration(trip.getId(), List.of(selected.getId()))).isEmpty();
    }

    private Trip trip(String name) {
        return trips.save(new Trip(owner.getUserId(), name,
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11)));
    }

    private TripDetailPlace folder(Trip target, int order) {
        return folders.save(TripDetailPlace.fromAnalysis(target.getId(), order, "장소" + order,
                BigDecimal.ZERO, BigDecimal.ZERO, takenAt, takenAt, null));
    }

    private TripAttachment photo(Trip target, TripDetailPlace folder, LocalDateTime time) {
        String key = UUID.randomUUID().toString();
        StoredFile file = files.save(StoredFile.uploaded(owner.getUserId(), "photo.jpg",
                "original/" + key, "image/jpeg"));
        TripAttachment attachment = TripAttachment.initial(target.getId(), file.getId(),
                "analyze/" + key, "preview/" + key);
        attachment.classify(folder.getId(), RegionOrigin.EXIF, time,
                BigDecimal.ZERO, BigDecimal.ZERO, 80);
        return attachments.save(attachment);
    }
}
