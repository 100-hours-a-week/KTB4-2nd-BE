package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.service.TripAccessService;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class UnclassifiedFolderMySqlRepositoryTest {
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private StoredFileRepository files;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private TripAccessService access;
    @Autowired private EntityManager entityManager;

    @Test
    void 사유별_개수와_대표에서_삭제파일_삭제첨부_다른여행_활성사진_NONE을_제외한다() {
        User owner = users.save(new User(UUID.randomUUID() + "@test.com", "회원"));
        Trip trip = trips.save(new Trip(owner.getUserId(), "여행", LocalDate.now(), LocalDate.now()));
        Trip other = trips.save(new Trip(owner.getUserId(), "다른여행", LocalDate.now(), LocalDate.now()));
        TripAttachment expected = photo(owner, trip, AttachmentIssue.BLURRY);
        photo(owner, trip, AttachmentIssue.UNCLEAR_LOCATION);
        TripAttachment deleted = photo(owner, trip, AttachmentIssue.BLURRY);
        deleted.softDelete(LocalDateTime.now());
        TripAttachment fileDeleted = photo(owner, trip, AttachmentIssue.BLURRY);
        files.findById(fileDeleted.getFileId()).orElseThrow().softDelete(LocalDateTime.now());
        photo(owner, other, AttachmentIssue.BLURRY);
        TripAttachment active = photo(owner, trip, AttachmentIssue.BLURRY);
        active.classify(null, RegionOrigin.UNKNOWN, null, null, null, 80);
        photo(owner, trip, AttachmentIssue.NONE);
        entityManager.flush();
        entityManager.clear();

        assertThat(attachments.countUnclassifiedByIssue(trip.getId())).containsExactlyInAnyOrder(
                new UnclassifiedFolderAttachmentCount(AttachmentIssue.BLURRY, 1),
                new UnclassifiedFolderAttachmentCount(AttachmentIssue.UNCLEAR_LOCATION, 1));
        assertThat(attachments.findUnclassifiedRepresentatives(trip.getId(), AttachmentIssue.BLURRY, PageRequest.of(0, 1)))
                .extracting(TripAttachment::getId).containsExactly(expected.getId());
        assertThat(attachments.findUnclassifiedRepresentatives(trip.getId(), AttachmentIssue.DUPLICATED, PageRequest.of(0, 1)))
                .isEmpty();
    }

    @Test
    void 대표는_생성시각_내림차순이며_동률이면_ID가_큰_사진이다() {
        User owner = users.save(new User(UUID.randomUUID() + "@test.com", "회원"));
        Trip trip = trips.save(new Trip(owner.getUserId(), "여행", LocalDate.now(), LocalDate.now()));
        TripAttachment first = photo(owner, trip, AttachmentIssue.BLURRY);
        TripAttachment second = photo(owner, trip, AttachmentIssue.BLURRY);
        TripAttachment older = photo(owner, trip, AttachmentIssue.BLURRY);
        entityManager.flush();
        LocalDateTime time = LocalDateTime.of(2026, 10, 8, 10, 0);
        for (TripAttachment photo : java.util.List.of(first, second, older)) {
            entityManager.createNativeQuery("update trip_attachments set created_at = :time where trip_attachment_id = :id")
                    .setParameter("time", photo == older ? time.minusMinutes(1) : time)
                    .setParameter("id", photo.getId()).executeUpdate();
        }
        entityManager.clear();
        assertThat(attachments.findUnclassifiedRepresentatives(trip.getId(), AttachmentIssue.BLURRY, PageRequest.of(0, 1)))
                .extracting(TripAttachment::getId).containsExactly(second.getId());
    }

    @Test
    void 여행_소유자만_조회할수있고_삭제여행은_거절한다() {
        User owner = users.save(new User(UUID.randomUUID() + "@test.com", "회원"));
        User other = users.save(new User(UUID.randomUUID() + "@test.com", "다른회원"));
        Trip trip = trips.save(new Trip(owner.getUserId(), "여행", LocalDate.now(), LocalDate.now()));
        assertThat(access.requireReadableTrip(trip.getId(), owner.getUserId()).getId()).isEqualTo(trip.getId());
        assertThatThrownBy(() -> access.requireReadableTrip(trip.getId(), other.getUserId()))
                .isInstanceOf(TripNotFoundException.class);
        entityManager.createNativeQuery("update trips set deleted_at = :time where trip_id = :id")
                .setParameter("time", LocalDateTime.now()).setParameter("id", trip.getId()).executeUpdate();
        entityManager.clear();
        assertThatThrownBy(() -> access.requireReadableTrip(trip.getId(), owner.getUserId()))
                .isInstanceOf(TripNotFoundException.class);
    }

    private TripAttachment photo(User owner, Trip trip, AttachmentIssue issue) {
        String key = UUID.randomUUID().toString();
        StoredFile file = files.save(StoredFile.uploaded(owner.getUserId(), "photo.jpg", "original/" + key, "image/jpeg"));
        TripAttachment photo = TripAttachment.initial(trip.getId(), file.getId(), "analyze/" + key, "preview/" + key);
        photo.unclassify(issue, RegionOrigin.UNKNOWN, null, null, null, null);
        return attachments.save(photo);
    }
}
