package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.SharedTestMysql;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import com.yeodam.yeodambe.user.service.UserStatsService;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.yeodam.yeodambe.trip.repository.TripAttachmentCountPolicyMySqlTest$SqlCapture"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({UserStatsService.class, TripAttachmentCountPolicyMySqlTest.Config.class})
class TripAttachmentCountPolicyMySqlTest {
    @Autowired
    private UserRepository users;
    @Autowired
    private TripRepository trips;
    @Autowired
    private StoredFileRepository files;
    @Autowired
    private TripAttachmentRepository attachments;
    @Autowired
    private UserStatsRepository stats;
    @Autowired
    private UserStatsService statsService;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private User owner;
    private Trip trip;

    @BeforeEach
    void prepare() {
        owner = users.save(new User(UUID.randomUUID() + "@count.test", "사진집계"));
        stats.save(new UserStats(owner));
        trip = trip();
    }

    @Test
    void 정상_8장과_미분류_2장만_전체_10장으로_집계한다() {
        for (int index = 0; index < 8; index++) {
            photo(ClassificationStatus.ACTIVE, "active-" + index);
        }
        photo(ClassificationStatus.UNCLASSIFIED, "unclassified-1");
        photo(ClassificationStatus.UNCLASSIFIED, "unclassified-2");
        photo(ClassificationStatus.DISCARDED, "discarded");
        photo(ClassificationStatus.DELETED, "deleted-state");
        flushAndClear();

        assertThat(count()).isEqualTo(10L);
    }

    @Test
    void 삭제된_첨부와_파일과_미완료_배치는_제외한다() {
        photo(ClassificationStatus.ACTIVE, "visible");
        TripAttachment removed = photo(ClassificationStatus.ACTIVE, "removed");
        removed.softDelete(LocalDateTime.now());
        TripAttachment removedFile = photo(ClassificationStatus.ACTIVE, "removed-file");
        files.findById(removedFile.getFileId()).orElseThrow().softDelete(LocalDateTime.now());
        pending(photo(ClassificationStatus.UNCLASSIFIED, "pending"));
        flushAndClear();

        assertThat(count()).isEqualTo(1L);
    }

    @Test
    void 표시에서_제외한_첨부도_원본_용량에는_유지한다() {
        photo(ClassificationStatus.ACTIVE, "original-shared");
        photo(ClassificationStatus.UNCLASSIFIED, "original-shared");
        photo(ClassificationStatus.DISCARDED, "discarded");
        pending(photo(ClassificationStatus.UNCLASSIFIED, "pending"));
        entityManager.flush();
        Statistics statistics = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        statsService.refreshFromActiveTrips(owner.getUserId());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(4L);
        System.out.println("STATS_EXPLAIN " + jdbc.queryForList(
                "EXPLAIN " + SqlCapture.lastUsage.get(), owner.getUserId(), ProcessingStatus.COMPLETED.name()));

        UserStats actual = stats.findByUser_UserId(owner.getUserId()).orElseThrow();
        assertThat(actual.getAttachmentCount()).isEqualTo(2L);
        assertThat(actual.getStorageUsedBytes()).isEqualTo(300L);
        assertThat(actual.getStorageReservedBytes()).isZero();
    }

    @Test
    void 여행_7개를_한_SQL로_집계하고_빈_여행은_결과에_추가하지_않는다() {
        List<Long> tripIds = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            trip = trip();
            tripIds.add(trip.getId());
            photo(ClassificationStatus.UNCLASSIFIED, "trip-" + index);
        }
        tripIds.add(trip().getId());
        flushAndClear();
        Statistics statistics = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<TripAttachmentCount> result = attachments.countNotDeletedByTripIds(tripIds);

        assertThat(result).hasSize(7);
        assertThat(result).allSatisfy(value -> assertThat(value.attachmentCount()).isEqualTo(1L));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1L);
        String countSql = SqlCapture.lastStatement.get();
        assertThat(countSql).contains("count(");
        System.out.println("COUNT_EXPLAIN " + jdbc.queryForList("EXPLAIN " + countSql, tripIds.toArray()));
    }

    private long count() {
        return attachments.countNotDeletedByTripIds(List.of(trip.getId())).stream()
                .mapToLong(TripAttachmentCount::attachmentCount)
                .sum();
    }

    private Trip trip() {
        Trip created = new Trip(owner.getUserId(), "집계여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(created, "processingStatus", ProcessingStatus.COMPLETED);
        return trips.save(created);
    }

    private TripAttachment photo(ClassificationStatus status, String objectKey) {
        StoredFile file = StoredFile.uploaded(owner.getUserId(), "photo.jpg", objectKey, "image/jpeg");
        file.storageSize(100L);
        files.save(file);
        TripAttachment attachment = TripAttachment.initial(trip.getId(), file.getId(), "analyze", "preview");
        ReflectionTestUtils.setField(attachment, "classificationStatus", status);
        return attachments.save(attachment);
    }

    private void pending(TripAttachment attachment) {
        AdditionalAttachmentUploadBatch batch = new AdditionalAttachmentUploadBatch(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                trip.getId(), owner.getUserId(), 1, 1, true
        );
        entityManager.persist(batch);
        AdditionalAttachmentUploadItem item = new AdditionalAttachmentUploadItem(
                batch, 1, "photo.jpg", "image/jpeg", 100L, "pending"
        );
        item.linkAttachment(attachment.getId());
        entityManager.persist(item);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    public static class SqlCapture implements org.hibernate.resource.jdbc.spi.StatementInspector {
        private static final ThreadLocal<String> lastStatement = new ThreadLocal<>();
        private static final ThreadLocal<String> lastUsage = new ThreadLocal<>();

        @Override
        public String inspect(String sql) {
            lastStatement.set(sql);
            if (sql.contains("case when") && sql.contains("original_size_bytes")) {
                lastUsage.set(sql);
            }
            return sql;
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
