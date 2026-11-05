package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.SharedTestMongo;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, StoryContentMigrationConcurrencyTest.MongoConfig.class})
class StoryContentMigrationConcurrencyTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class MongoConfig {
        @Bean @Primary
        MongoConnectionDetails isolatedMongo() {
            return () -> new com.mongodb.ConnectionString(
                    SharedTestMongo.connectionString("story_migration_concurrency_test"));
        }
    }

    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired StoryContentMigrationService service;
    @Autowired StoryContentRepository contents;

    @Test
    void concurrentMigrationsPreserveOneDocument() throws Exception {
        Long storyId = tx.execute(status -> {
            var user = new User(UUID.randomUUID() + "@concurrency.test", "이관");
            em.persist(user);
            var trip = new Trip(user.getUserId(), "여행", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3));
            em.persist(trip);
            var story = new Story(trip.getId(), Story.Mood.PLAIN, "원본 본문");
            story.complete();
            em.persist(story);
            trip.changeCurrentStory(story.getId());
            em.flush();
            return story.getId();
        });
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("시작 대기 초과");
                service.migrateOne(storyId);
                return null;
            });
            var second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("시작 대기 초과");
                service.migrateOne(storyId);
                return null;
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);

            StoryContentDocument saved = contents.findById(storyId).orElseThrow();
            assertThat(saved.getStorySummary()).isEqualTo("원본 본문");
            assertThat(saved.getVersion()).isZero();
            assertThat(contents.count()).isEqualTo(1);
        } finally {
            start.countDown();
            contents.deleteById(storyId);
        }
    }
}
