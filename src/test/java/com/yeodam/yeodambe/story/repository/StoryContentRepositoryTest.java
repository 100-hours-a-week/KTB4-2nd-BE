package com.yeodam.yeodambe.story.repository;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.yeodam.yeodambe.story.config.StoryMongoConfiguration;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.entity.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@ResourceLock("java.util.TimeZone")
class StoryContentRepositoryTest {
    @Container
    static final GenericContainer<?> mongo = new GenericContainer<>("mongo:8.0")
            .withExposedPorts(27017);

    static MongoClient client;
    static MongoTemplate template;
    static StoryContentRepository repository;
    static MongoCustomConversions conversions;

    @BeforeAll
    static void connect() {
        client = MongoClients.create("mongodb://" + mongo.getHost()
                + ":" + mongo.getMappedPort(27017));
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(StoryMongoConfiguration.class)) {
            conversions = context.getBean(MongoCustomConversions.class);
        }
        template = createTemplate();
        repository = new MongoRepositoryFactory(template)
                .getRepository(StoryContentRepository.class);
    }

    @AfterAll
    static void close() {
        if (client != null) {
            client.close();
        }
    }

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void preservesNumericIdBlockOrderDatesAndDeletionHistory() {
        Instant timestamp = Instant.parse("2026-11-03T03:30:00.123Z");
        StoryContentBlock deleted = block(30L, timestamp, timestamp);
        StoryContentBlock active = block(10L, timestamp, null);
        StoryContentDocument saved = repository.save(new StoryContentDocument(
                101L, Story.Mood.PLAIN, "여행 요약", List.of(7L, 8L),
                List.of(deleted, active), 31L
        ));

        StoryContentDocument found = repository.findById(101L).orElseThrow();

        assertThat(found.getId()).isEqualTo(101L);
        assertThat(found.getMood()).isEqualTo(Story.Mood.PLAIN);
        assertThat(found.getStorySummary()).isEqualTo("여행 요약");
        assertThat(found.getSelectedPlaceIds()).containsExactly(7L, 8L);
        assertThat(found.getBlocks()).containsExactly(deleted, active);
        assertThat(found.getNextBlockId()).isEqualTo(31L);
        assertThat(saved.getVersion()).isZero();
        assertThat(found.getVersion()).isEqualTo(saved.getVersion());
        assertThat(template.getCollection("story_contents")
                .find(new org.bson.Document("_id", 101L)).first()
                .get("_id")).isInstanceOf(Long.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Seoul", "America/Los_Angeles"})
    void preservesCalendarDateAndInstantsAcrossServerTimeZones(String readZone) {
        TimeZone originalZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
            Instant timestamp = Instant.parse("2026-11-03T00:00:00.123Z");
            StoryContentBlock original = block(1L, timestamp, timestamp);
            MongoTemplate writer = createTemplate();
            writer.save(new StoryContentDocument(103L, Story.Mood.PLAIN,
                    "날짜 검증", List.of(7L), List.of(original), 2L));

            org.bson.Document stored = writer.getCollection("story_contents")
                    .find(new org.bson.Document("_id", 103L)).first();
            assertThat(stored).isNotNull();
            org.bson.Document storedBlock = stored
                    .getList("blocks", org.bson.Document.class).getFirst();
            assertThat(storedBlock.get("sourceDate")).isEqualTo("2026-10-01");
            for (String field : List.of("createdAt", "updatedAt", "deletedAt")) {
                assertThat(storedBlock.get(field)).isInstanceOf(Date.class);
                assertThat(storedBlock.getDate(field).toInstant()).isEqualTo(timestamp);
            }

            TimeZone.setDefault(TimeZone.getTimeZone(readZone));
            MongoTemplate reader = createTemplate();
            StoryContentDocument found = reader.findById(103L, StoryContentDocument.class);

            assertThat(found).isNotNull();
            assertThat(found.getBlocks()).containsExactly(original);
        } finally {
            TimeZone.setDefault(originalZone);
        }
    }

    @Test
    void rejectsStaleSaveWithoutOverwritingNewerContent() {
        repository.save(new StoryContentDocument(
                102L, Story.Mood.PLAIN, "기존 요약", List.of(7L),
                List.of(block(1L, Instant.parse("2026-11-03T03:30:00Z"), null)), 2L
        ));
        StoryContentDocument first = repository.findById(102L).orElseThrow();
        StoryContentDocument stale = repository.findById(102L).orElseThrow();
        first.updateContent(Story.Mood.PLAIN, "먼저 저장한 요약", first.getBlocks());
        stale.updateContent(Story.Mood.PLAIN, "오래된 요청의 요약", stale.getBlocks());

        StoryContentDocument updated = repository.save(first);

        assertThat(updated.getVersion()).isEqualTo(1L);
        assertThatThrownBy(() -> repository.save(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(repository.findById(102L).orElseThrow().getStorySummary())
                .isEqualTo("먼저 저장한 요약");
    }

    @Test
    void savesContentAndNextIdTogetherWithoutReusingDeletedBlockId() {
        Instant timestamp = Instant.parse("2026-11-03T03:30:00Z");
        StoryContentBlock deleted = block(30L, timestamp, timestamp);
        StoryContentBlock active = block(10L, timestamp, null);
        repository.save(new StoryContentDocument(104L, Story.Mood.PLAIN,
                "기존 요약", List.of(7L), List.of(active, deleted), 31L));
        StoryContentDocument document = repository.findById(104L).orElseThrow();

        long newId = document.issueBlockId();
        StoryContentBlock added = block(newId, timestamp, null);
        document.updateContent(Story.Mood.HUMOROUS, "수정한 요약",
                List.of(added, active, deleted));
        repository.save(document);

        StoryContentDocument found = repository.findById(104L).orElseThrow();
        assertThat(newId).isEqualTo(31L);
        assertThat(found.getMood()).isEqualTo(Story.Mood.HUMOROUS);
        assertThat(found.getStorySummary()).isEqualTo("수정한 요약");
        assertThat(found.getSelectedPlaceIds()).containsExactly(7L);
        assertThat(found.getBlocks()).containsExactly(added, active, deleted);
        assertThat(found.getNextBlockId()).isEqualTo(32L);
        assertThat(found.issueBlockId()).isEqualTo(32L);
    }

    @Test
    void rejectsStaleBlockAllocationAndPreservesWinningDocument() {
        Instant timestamp = Instant.parse("2026-11-03T03:30:00Z");
        repository.save(new StoryContentDocument(105L, Story.Mood.PLAIN,
                "기존 요약", List.of(7L), List.of(block(10L, timestamp, null)), 11L));
        StoryContentDocument first = repository.findById(105L).orElseThrow();
        StoryContentDocument stale = repository.findById(105L).orElseThrow();
        long firstId = first.issueBlockId();
        long staleId = stale.issueBlockId();
        StoryContentBlock firstBlock = block(firstId, timestamp, null);
        first.updateContent(Story.Mood.PLAIN, "첫 요청", List.of(firstBlock));
        stale.updateContent(Story.Mood.PLAIN, "두 번째 요청",
                List.of(block(staleId, timestamp, timestamp)));
        repository.save(first);

        assertThat(firstId).isEqualTo(staleId);
        assertThatThrownBy(() -> repository.save(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
        StoryContentDocument found = repository.findById(105L).orElseThrow();
        assertThat(found.getBlocks()).containsExactly(firstBlock);
        assertThat(found.getStorySummary()).isEqualTo("첫 요청");
        assertThat(found.getNextBlockId()).isEqualTo(12L);
    }

    private StoryContentBlock block(Long id, Instant timestamp,
                                    Instant deletedAt) {
        return new StoryContentBlock(id, id + 100L, 7L,
                LocalDate.of(2026, 10, 1), "첫째 날", "사진 설명", "메모",
                timestamp, timestamp, deletedAt);
    }

    private static MongoTemplate createTemplate() {
        SimpleMongoClientDatabaseFactory factory =
                new SimpleMongoClientDatabaseFactory(client, "story_repository_test");
        MongoMappingContext mappingContext = new MongoMappingContext();
        mappingContext.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        mappingContext.setInitialEntitySet(Set.of(
                StoryContentDocument.class, StoryContentBlock.class));
        mappingContext.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(
                new DefaultDbRefResolver(factory), mappingContext);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        return new MongoTemplate(factory, converter);
    }
}
