package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.SharedTestMysql;
import com.yeodam.yeodambe.SharedTestMongo;
import com.yeodam.yeodambe.story.config.StoryMongoConfiguration;
import com.yeodam.yeodambe.story.document.StoryContentBlock;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.entity.Story;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoryRepositoryAutoConfigurationTest {
    @TestConfiguration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackages = "com.yeodam.yeodambe")
    static class RepositoryConfiguration {
        @Bean
        JdbcConnectionDetails mysqlConnectionDetails() throws SQLException {
            return SharedTestMysql.createDatabase();
        }
    }

    @Test
    void registersJpaAndMongoRepositoriesWithConfiguredDateConversions() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class,
                        DataJpaRepositoriesAutoConfiguration.class,
                        MongoAutoConfiguration.class,
                        DataMongoAutoConfiguration.class,
                        DataMongoRepositoriesAutoConfiguration.class))
                .withUserConfiguration(RepositoryConfiguration.class, StoryMongoConfiguration.class)
                .withPropertyValues(
                        "spring.mongodb.uri=" + SharedTestMongo.connectionString("story_auto_configuration_test"),
                        "spring.jpa.hibernate.ddl-auto=validate")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(StoryRepository.class);
                    assertThat(context).hasSingleBean(StoryContentRepository.class);
                    EntityManagerFactory entityManagerFactory =
                            context.getBean(EntityManagerFactory.class);
                    assertThat(entityManagerFactory.getMetamodel().getEntities())
                            .extracting(entity -> entity.getJavaType().getName())
                            .contains(Story.class.getName())
                            .doesNotContain(StoryContentDocument.class.getName());
                    assertThat(context.getBean(StoryRepository.class).count()).isZero();

                    Instant timestamp = Instant.parse("2026-11-03T00:00:00.123Z");
                    StoryContentBlock block = new StoryContentBlock(1L, 2L, 3L,
                            LocalDate.of(2026, 10, 1), "첫째 날", "사진 설명", null,
                            timestamp, timestamp, null);
                    StoryContentRepository repository = context.getBean(StoryContentRepository.class);
                    repository.save(new StoryContentDocument(1L, Story.Mood.PLAIN,
                            "요약", List.of(3L), List.of(block), 2L));
                    assertThat(repository.findById(1L).orElseThrow().getBlocks())
                            .containsExactly(block);
                    org.bson.Document stored = context.getBean(MongoTemplate.class)
                            .getCollection("story_contents").find().first();
                    assertThat(stored).isNotNull();
                    assertThat(stored.getList("blocks", org.bson.Document.class)
                            .getFirst().get("sourceDate")).isEqualTo("2026-10-01");
                });
    }
}
