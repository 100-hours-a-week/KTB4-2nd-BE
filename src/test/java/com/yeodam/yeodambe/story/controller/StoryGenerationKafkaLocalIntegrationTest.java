package com.yeodam.yeodambe.story.controller;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.story.entity.StoryGenerationOutbox;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.repository.StoryGenerationOutboxRepository;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.jwt.ActiveLoginSessionValidator;
import jakarta.servlet.http.Cookie;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "YEODAM_KAFKA_INTEGRATION", matches = "true")
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${KAFKA_TEST_BOOTSTRAP_SERVERS:localhost:9092}",
        "app.kafka.topics.story-create-request=ai-jobs"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class StoryGenerationKafkaLocalIntegrationTest {

    private static final String BOOTSTRAP_SERVERS = System.getenv()
            .getOrDefault("KAFKA_TEST_BOOTSTRAP_SERVERS", "localhost:9092");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AccessTokenIssuer issuer;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripDetailPlaceRepository folders;
    @Autowired private StoredFileRepository files;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private StoryGenerationJobRepository jobs;
    @Autowired private StoryGenerationOutboxRepository outboxes;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private ActiveLoginSessionValidator sessions;
    @MockitoBean private CsrfTokenStore csrf;

    private User owner;
    private Trip trip;
    private TripDetailPlace folder;
    private StoredFile file;
    private TripAttachment photo;

    @BeforeEach
    void prepare() {
        when(sessions.validate(any(Jwt.class))).thenReturn(OAuth2TokenValidatorResult.success());
        when(csrf.find("kafka-test-browser")).thenReturn("csrf-test");
        owner = users.save(new User(UUID.randomUUID() + "@yeodam.test", "스토리회원"));
        trip = trips.save(new Trip(owner.getUserId(), "검증여행",
                LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 12)));
        LocalDateTime takenAt = LocalDateTime.of(2026, 10, 11, 10, 0);
        folder = folders.save(TripDetailPlace.fromAnalysis(trip.getId(), 1, "검증 장소",
                BigDecimal.ZERO, BigDecimal.ZERO, takenAt, takenAt, null));
        file = files.save(StoredFile.uploaded(owner.getUserId(), "photo.jpg",
                "local-test/original/" + UUID.randomUUID(), "image/jpeg"));
        var attachment = TripAttachment.initial(trip.getId(), file.getId(),
                "local-test/analysis/photo.jpg", "local-test/preview/photo.jpg");
        attachment.classify(folder.getId(), RegionOrigin.EXIF, takenAt, BigDecimal.ZERO, BigDecimal.ZERO, 80);
        photo = attachments.saveAndFlush(attachment);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM story_generation_outbox WHERE generation_id IN "
                + "(SELECT generation_id FROM story_generation_jobs WHERE trip_id = ?)", trip.getId());
        jdbc.update("DELETE FROM story_generation_places WHERE generation_id IN "
                + "(SELECT generation_id FROM story_generation_jobs WHERE trip_id = ?)", trip.getId());
        jdbc.update("DELETE FROM story_generation_jobs WHERE trip_id = ?", trip.getId());
        jdbc.update("DELETE FROM trip_attachments WHERE trip_id = ?", trip.getId());
        jdbc.update("DELETE FROM trip_detail_places WHERE trip_id = ?", trip.getId());
        trips.deleteById(trip.getId());
        files.deleteById(file.getId());
        users.deleteById(owner.getUserId());
    }

    @Test
    void apiCommitsOutboxPublishesExactJsonAndReturnsAccepted() throws Exception {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS,
                ConsumerConfig.GROUP_ID_CONFIG, "local-story-verification-" + UUID.randomUUID(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        );
        try (var consumer = new KafkaConsumer<String, String>(config)) {
            var partitions = consumer.partitionsFor("ai-jobs").stream()
                    .map(partition -> new TopicPartition(partition.topic(), partition.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToEnd(partitions);
            partitions.forEach(consumer::position);
            String path = "/trips/" + trip.getId() + "/story-generations";
            String requestId = "local-kafka-" + UUID.randomUUID();

            var started = mvc.perform(post("/api" + path).contextPath("/api").servletPath(path)
                            .cookie(new Cookie("accessToken", issuer.issue(owner.getUserId(), "test-session")),
                                    new Cookie("CSRF_CONTEXT", "kafka-test-browser"))
                            .header("X-CSRF-TOKEN", "csrf-test").header("X-Request-ID", requestId)
                            .contentType("application/json")
                            .content("{\"mood\":\"EMOTIONAL\",\"tripPlaceIds\":[" + folder.getId() + "]}"))
                    .andExpect(request().asyncStarted()).andReturn();
            var response = mvc.perform(asyncDispatch(started)).andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.message").value("STORY_GENERATION_ACCEPTED"))
                    .andExpect(jsonPath("$.data.status").value("QUEUED")).andReturn();
            Long generationId = mapper.readTree(response.getResponse().getContentAsString())
                    .path("data").path("generationId").asLong();
            var job = jobs.findById(generationId).orElseThrow();
            var outbox = outboxes.findByGenerationId(generationId).orElseThrow();
            assertThat(outbox.getStatus()).isEqualTo(StoryGenerationOutbox.Status.PUBLISHED);
            assertThat(outbox.getPublishedAt()).isNotNull();
            assertThat(outbox.getAttemptCount()).isEqualTo(1);
            assertThat(outbox.getRequestId()).isEqualTo(requestId);

            String received = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (received == null && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofSeconds(1))) {
                    if (mapper.readTree(record.value()).path("execution_id").asString().equals(job.getExecutionId())) {
                        received = record.value();
                        assertThat(record.key()).isEqualTo(trip.getId().toString());
                        break;
                    }
                }
            }
            assertThat(received).as("ai-jobs에서 이번 실행의 메시지 수신").isEqualTo(outbox.getPayload());
            var json = mapper.readTree(received);
            assertThat(json.path("type").asString()).isEqualTo("story");
            assertThat(json.path("trip_id").asLong()).isEqualTo(trip.getId());
            var sentPhoto = json.path("places").get(0).path("attachments").get(0);
            assertThat(sentPhoto.path("trip_attachment_id").asLong()).isEqualTo(photo.getId());
            assertThat(sentPhoto.path("analyze_storage_key").asString()).isEqualTo(photo.getAnalyzeStorageKey());
        }
    }
}
