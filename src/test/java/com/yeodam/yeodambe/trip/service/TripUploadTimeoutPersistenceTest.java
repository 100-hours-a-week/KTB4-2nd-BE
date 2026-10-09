package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
class TripUploadTimeoutPersistenceTest {
    @Autowired private TripRepository trips;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private StoredFileRepository files;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 대기_만료는_여행_상태만_바꾸고_사진과_저장키를_보존한다() {
        User user = users.saveAndFlush(new User("timeout@example.com", "사용자"));
        Trip trip = trips.saveAndFlush(new Trip(user.getUserId(), "여행", LocalDate.now(), LocalDate.now()));
        StoredFile file = files.saveAndFlush(StoredFile.uploaded(user.getUserId(), "photo.jpg", "original", "image/jpeg"));
        TripAttachment attachment = attachments.saveAndFlush(TripAttachment.initial(trip.getId(), file.getId(), "analyze", "preview"));
        Instant start = Instant.parse("2026-10-02T00:00:00Z");
        InitialUploadExecutionRegistry registry = new InitialUploadExecutionRegistry(Clock.fixed(start, ZoneOffset.UTC));
        var reservation = registry.reserveBatch(trip.getId(), 1, 2);
        registry.completeBatch(trip.getId(), reservation.executionId(), 1, 1,
                List.of(new InitialUploadExecutionRegistry.StoredPhoto(file, attachment,
                        new DerivedPhotoKeys("original", "analyze", "preview"))), false);
        TripUploadTimeoutService service = new TripUploadTimeoutService(registry, trips,
                new TransactionTemplate(transactionManager), Clock.fixed(start.plusSeconds(600), ZoneOffset.UTC));

        service.failExpiredUploads();
        entityManager.clear();

        Trip savedTrip = trips.findById(trip.getId()).orElseThrow();
        assertThat(savedTrip.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(savedTrip.getDeletedAt()).isNull();
        StoredFile savedFile = files.findById(file.getId()).orElseThrow();
        assertThat(savedFile.getDeletedAt()).isNull();
        assertThat(savedFile.getObjectKey()).isEqualTo("original");
        TripAttachment savedAttachment = attachments.findById(attachment.getId()).orElseThrow();
        assertThat(savedAttachment.getDeletedAt()).isNull();
        assertThat(savedAttachment.getAnalyzeStorageKey()).isEqualTo("analyze");
        assertThat(savedAttachment.getPreviewStorageKey()).isEqualTo("preview");
        assertThat(registry.isCurrent(trip.getId(), reservation.executionId())).isFalse();
    }
}
