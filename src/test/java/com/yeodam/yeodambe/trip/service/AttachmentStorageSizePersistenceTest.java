package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AttachmentStorageSizePersistenceTest {
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private StoredFileRepository files;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private InitialUploadExecutionRegistry executions;
    @Autowired private TripAttachmentTransactionService transactions;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 첨부_저장_트랜잭션_종료_후_실제_용량을_다시_조회한다(boolean hasDisplay) {
        String suffix = hasDisplay ? "heic" : "jpeg";
        User user = users.saveAndFlush(new User("size-" + suffix + "@yeodam.test", "용량검증"));
        Trip trip = trips.saveAndFlush(new Trip(user.getUserId(), "용량검증", LocalDate.now(), LocalDate.now()));
        String execution = executions.reserve(trip.getId());
        String mime = hasDisplay ? "image/heic" : "image/jpeg";
        var upload = new MockMultipartFile("attachments[]", "photo." + suffix, mime, new byte[]{1, 2, 3, 4});
        var photo = new DerivedPhotoKeys("original/" + suffix, "analyze/" + suffix,
                "preview/" + suffix, hasDisplay ? "display/" + suffix : null,
                null, null, null, null, 4L, 100L, 50L, hasDisplay ? 200L : null);

        var saved = transactions.saveFilesAndAttachments(trip.getId(), user.getUserId(), execution,
                List.of(upload), List.of(photo.originalKey()), List.of(mime), List.of(photo));

        var original = files.findById(saved.originals().getFirst().getId()).orElseThrow();
        var attachment = attachments.findById(saved.attachments().getFirst().getId()).orElseThrow();
        assertThat(original.getOriginalSizeBytes()).isEqualTo(4L);
        assertThat(attachment.getAnalyzeSizeBytes()).isEqualTo(100L);
        assertThat(attachment.getPreviewSizeBytes()).isEqualTo(50L);
        assertThat(attachment.getDisplaySizeBytes()).isEqualTo(hasDisplay ? 200L : null);
    }
}
