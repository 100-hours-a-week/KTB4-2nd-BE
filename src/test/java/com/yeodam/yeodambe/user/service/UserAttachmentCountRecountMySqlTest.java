package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.SharedTestMysql;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UserAttachmentCountRecountMySqlTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserAttachmentCountRecountMySqlTest {
    @Autowired
    private DataSource dataSource;
    @Autowired
    private UserRepository users;
    @Autowired
    private UserStatsRepository stats;
    @Autowired
    private TripRepository trips;
    @Autowired
    private StoredFileRepository files;
    @Autowired
    private TripAttachmentRepository attachments;
    @Autowired
    private com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadBatchRepository batches;
    @Autowired
    private com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadItemRepository items;
    private User owner;
    private Trip trip;
    private TripAttachment discarded;

    @BeforeEach
    void prepare() {
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@recount.test", "통계보정"));
        UserStats initial = new UserStats(owner);
        initial.replaceActiveTripUsage(3L, 99L, 700L);
        initial.reserveStorage(100L);
        stats.saveAndFlush(initial);
        Trip created = new Trip(owner.getUserId(), "보정여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(created, "processingStatus", ProcessingStatus.COMPLETED);
        trip = trips.saveAndFlush(created);
        photo(ClassificationStatus.ACTIVE);
        photo(ClassificationStatus.UNCLASSIFIED);
        discarded = photo(ClassificationStatus.DISCARDED);
    }

    @Test
    void 사진_개수만_보정하고_재실행해도_다른_통계는_유지한다() throws Exception {
        recount(owner.getUserId());
        recount(owner.getUserId());

        UserStats actual = stats.findByUser_UserId(owner.getUserId()).orElseThrow();
        assertThat(actual.getAttachmentCount()).isEqualTo(2L);
        assertThat(actual.getTripCount()).isEqualTo(3L);
        assertThat(actual.getStoryCount()).isZero();
        assertThat(actual.getStorageUsedBytes()).isEqualTo(700L);
        assertThat(actual.getStorageReservedBytes()).isEqualTo(100L);
        assertThat(actual.getStorageMaximumBytes()).isEqualTo(30_000_000_000L);
    }

    @Test
    void 다른_사용자와_탈퇴_사용자의_통계는_바꾸지_않는다() throws Exception {
        User other = users.saveAndFlush(new User(UUID.randomUUID() + "@recount.test", "다른회원"));
        UserStats otherStats = new UserStats(other);
        otherStats.replaceActiveTripUsage(1L, 77L, 100L);
        stats.saveAndFlush(otherStats);
        recount(other.getUserId());
        assertThat(stats.findByUser_UserId(owner.getUserId()).orElseThrow().getAttachmentCount()).isEqualTo(99L);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE users SET deleted_at = NOW() WHERE user_id = ?")) {
            statement.setLong(1, owner.getUserId());
            statement.executeUpdate();
        }
        recount(owner.getUserId());
        assertThat(stats.findByUser_UserId(owner.getUserId()).orElseThrow().getAttachmentCount()).isEqualTo(99L);
    }

    @Test
    void 미완료_배치와_삭제된_파일은_보정에서_제외한다() throws Exception {
        TripAttachment pending = photo(ClassificationStatus.UNCLASSIFIED);
        com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch batch = batches.saveAndFlush(
                new com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch(
                        UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                        trip.getId(), owner.getUserId(), 1, 1, true));
        com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem item =
                new com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem(
                        batch, 1, "photo.jpg", "image/jpeg", 100L, "pending");
        item.linkAttachment(pending.getId());
        items.saveAndFlush(item);
        TripAttachment removed = photo(ClassificationStatus.ACTIVE);
        StoredFile removedFile = files.findById(removed.getFileId()).orElseThrow();
        removedFile.softDelete(LocalDateTime.now());
        files.saveAndFlush(removedFile);

        recount(owner.getUserId());

        assertThat(stats.findByUser_UserId(owner.getUserId()).orElseThrow().getAttachmentCount()).isEqualTo(2L);
    }

    @Test
    void 통계_잠금을_기다린_뒤_커밋된_사진_상태를_집계한다() throws Exception {
        try (Connection writer = dataSource.getConnection();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try (PreparedStatement lock = writer.prepareStatement("SELECT user_stats_id FROM user_stats WHERE user_id = ? FOR UPDATE")) {
                lock.setLong(1, owner.getUserId());
                try (ResultSet result = lock.executeQuery()) {
                    assertThat(result.next()).isTrue();
                }
            }
            try (PreparedStatement update = writer.prepareStatement("UPDATE trip_attachments SET classification_status = 'ACTIVE' WHERE trip_attachment_id = ?")) {
                update.setLong(1, discarded.getId());
                update.executeUpdate();
            }
            CountDownLatch started = new CountDownLatch(1);
            Future<?> correction = executor.submit(() -> {
                started.countDown();
                recount(owner.getUserId());
                return null;
            });
            try {
                assertThat(started.await(5L, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> correction.get(200L, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                writer.commit();
            }
            correction.get(10L, TimeUnit.SECONDS);
        }
        assertThat(stats.findByUser_UserId(owner.getUserId()).orElseThrow().getAttachmentCount()).isEqualTo(3L);
    }

    private TripAttachment photo(ClassificationStatus status) {
        StoredFile file = files.saveAndFlush(StoredFile.uploaded(
                owner.getUserId(), "photo.jpg", UUID.randomUUID().toString(), "image/jpeg"
        ));
        TripAttachment attachment = TripAttachment.initial(trip.getId(), file.getId(), "analyze", "preview");
        ReflectionTestUtils.setField(attachment, "classificationStatus", status);
        return attachments.saveAndFlush(attachment);
    }

    private void recount(Long userId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement target = connection.prepareStatement("SET @attachment_count_target_user_id = ?")) {
            target.setLong(1, userId);
            target.execute();
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("scripts/sql/recount-user-attachment-count.sql"));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        JdbcConnectionDetails mysql() throws SQLException {
            return SharedTestMysql.createDatabase();
        }
    }
}
