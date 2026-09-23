package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.AttachmentIssue;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.RegionOrigin;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({
        InitialUploadExecutionRegistry.class,
        TripAttachmentTransactionService.class,
        TripAnalysisResultService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TripAttachmentTransactionPersistenceTest {

    @Autowired
    private TripAttachmentTransactionService attachmentTransactions;
    @Autowired
    private TripAnalysisResultService analysisResults;
    @Autowired
    private InitialUploadExecutionRegistry executions;
    @Autowired
    private UserRepository users;
    @Autowired
    private TripRepository trips;
    @Autowired
    private StoredFileRepository files;
    @Autowired
    private TripAttachmentRepository attachments;
    @Autowired
    private TripDetailPlaceRepository places;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 파생_키_검증이_실패하면_먼저_저장한_원본도_롤백한다() {
        User user = users.saveAndFlush(new User("attachment-rollback@yeodam.test", "첨부롤백"));
        Trip trip = trips.saveAndFlush(new Trip(
                user.getUserId(), "첨부롤백", LocalDate.now(), LocalDate.now()));
        String executionId = executions.reserve(trip.getId());
        var upload = new MockMultipartFile(
                "attachments[]", "photo.jpg", "image/jpeg", new byte[]{1});
        var wrongDerived = new DerivedPhotoKeys(
                "different-original", "analyze", "preview", null, null, null, null);
        long fileCount = files.count();
        long attachmentCount = attachments.count();

        assertThatThrownBy(() -> attachmentTransactions.saveFilesAndAttachments(
                trip.getId(),
                user.getUserId(),
                executionId,
                List.of(upload),
                List.of("original"),
                List.of("image/jpeg"),
                List.of(wrongDerived)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("원본과 파생 사진의 순서가 다릅니다.");

        assertThat(files.count()).isEqualTo(fileCount);
        assertThat(attachments.count()).isEqualTo(attachmentCount);
    }

    @Test
    void AI_결과_저장_중_검증이_실패하면_완료_상태와_장소를_롤백한다() {
        User user = users.saveAndFlush(new User("analysis-rollback@yeodam.test", "결과롤백"));
        Trip trip = trips.saveAndFlush(new Trip(
                user.getUserId(), "결과롤백", LocalDate.now(), LocalDate.now()));
        StoredFile file = files.saveAndFlush(StoredFile.uploaded(
                user.getUserId(), "photo.jpg", "original", "image/jpeg"));
        TripAttachment classified = attachments.saveAndFlush(TripAttachment.initial(
                trip.getId(), file.getId(), "analyze-1", "preview-1"));
        TripAttachment invalid = attachments.saveAndFlush(TripAttachment.initial(
                trip.getId(), file.getId(), "analyze-2", "preview-2"));
        String executionId = executions.reserve(trip.getId());

        var result = json.readTree("""
                {
                  "places": [{
                    "place_id": "p1",
                    "latitude": 33.45,
                    "longitude": 126.94,
                    "first_taken_at": null,
                    "last_taken_at": null,
                    "representative_attachment_id": %d,
                    "attachments": [{
                      "trip_attachment_id": %d,
                      "taken_at": null,
                      "latitude": 33.45,
                      "longitude": 126.94,
                      "region_origin": "EXIF",
                      "evaluation": 91
                    }]
                  }],
                  "unclassified": [{
                    "trip_attachment_id": %d,
                    "issue": "INVALID_ISSUE",
                    "region_origin": "UNKNOWN",
                    "taken_at": null,
                    "latitude": null,
                    "longitude": null,
                    "evaluation": 31
                  }]
                }
                """.formatted(classified.getId(), classified.getId(), invalid.getId()));

        assertThatThrownBy(() -> analysisResults.saveCompleted(
                trip.getId(),
                user.getUserId(),
                executionId,
                List.of(classified, invalid),
                result
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("AI 사진 이슈가 올바르지 않습니다.");

        assertThat(trips.findById(trip.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(places.countByTripIdAndDeletedAtIsNull(trip.getId())).isZero();
        assertThat(attachments.findAllById(List.of(classified.getId(), invalid.getId())))
                .allSatisfy(attachment -> {
                    assertThat(attachment.getClassificationStatus())
                            .isEqualTo(ClassificationStatus.UNCLASSIFIED);
                    assertThat(attachment.getRegionOrigin()).isEqualTo(RegionOrigin.UNKNOWN);
                    assertThat(attachment.getIssue()).isEqualTo(AttachmentIssue.NONE);
                });
    }
}
