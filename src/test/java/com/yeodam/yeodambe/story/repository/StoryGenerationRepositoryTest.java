package com.yeodam.yeodambe.story.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.entity.StoryGenerationPlace;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class StoryGenerationRepositoryTest {

    @Autowired private StoryGenerationJobRepository jobs;
    @Autowired private StoryGenerationPlaceRepository places;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User owner;
    private Trip trip;

    @BeforeEach
    void prepare() {
        owner = users.save(new User(UUID.randomUUID() + "@yeodam.test", "스토리회원"));
        trip = trips.save(new Trip(owner.getUserId(), "스토리여행",
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11)));
    }

    @Test
    void 마이그레이션된_작업과_선택_폴더를_저장하고_선택순으로_재조회한다() {
        String executionId = UUID.randomUUID().toString();
        StoryGenerationJob job = jobs.saveAndFlush(job(trip, executionId));
        TripDetailPlace first = place(trip, 1);
        TripDetailPlace second = place(trip, 2);
        places.save(new StoryGenerationPlace(job.getId(), second.getId(), 2));
        places.save(new StoryGenerationPlace(job.getId(), first.getId(), 1));

        Trip otherTrip = otherTrip();
        StoryGenerationJob otherJob = jobs.save(job(otherTrip, UUID.randomUUID().toString()));
        TripDetailPlace otherPlace = place(otherTrip, 1);
        places.save(new StoryGenerationPlace(otherJob.getId(), otherPlace.getId(), 1));
        entityManager.flush();
        entityManager.clear();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '14' AND success = 1
                """, Integer.class)).isEqualTo(1);
        StoryGenerationJob found = jobs.findById(job.getId()).orElseThrow();
        assertThat(found.getId()).isPositive();
        assertThat(found.getExecutionId()).isEqualTo(executionId);
        assertThat(found.getTripId()).isEqualTo(trip.getId());
        assertThat(found.getUserId()).isEqualTo(owner.getUserId());
        assertThat(found.getMood()).isEqualTo(Story.Mood.PLAIN);
        assertThat(found.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
        assertThat(found.getProgress()).isZero();
        assertThat(found.getStoryId()).isNull();
        assertThat(found.getErrorCode()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT active_trip_id FROM story_generation_jobs WHERE generation_id = ?",
                Long.class, job.getId())).isEqualTo(trip.getId());
        var selected = places.findAllByGenerationIdOrderByOrderNumberAsc(job.getId());
        assertThat(selected).extracting(StoryGenerationPlace::getTripPlaceId)
                .containsExactly(first.getId(), second.getId());
        assertThat(selected).extracting(StoryGenerationPlace::getOrderNumber)
                .containsExactly(1, 2);
        assertThat(selected).allSatisfy(value -> {
            assertThat(value.getId()).isPositive();
            assertThat(value.getGenerationId()).isEqualTo(job.getId());
            assertThat(value.getCreatedAt()).isNotNull();
        });
    }

    @ParameterizedTest
    @EnumSource(value = StoryGenerationJob.Status.class, names = {"QUEUED", "PROCESSING"})
    void 진행중인_작업이_있으면_같은_여행의_추가_작업을_DB가_거부한다(
            StoryGenerationJob.Status status
    ) {
        StoryGenerationJob first = jobs.saveAndFlush(job(trip, UUID.randomUUID().toString()));
        jdbcTemplate.update("UPDATE story_generation_jobs SET status = ? WHERE generation_id = ?",
                status.name(), first.getId());

        assertThatThrownBy(() -> jobs.saveAndFlush(job(trip, UUID.randomUUID().toString())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_story_generation_active_trip");
    }

    @ParameterizedTest
    @EnumSource(value = StoryGenerationJob.Status.class, names = {"COMPLETED", "FAILED", "CANCELED"})
    void 작업이_종료되면_이력을_보존하며_같은_여행의_새_작업을_허용한다(
            StoryGenerationJob.Status status
    ) {
        StoryGenerationJob previous = jobs.saveAndFlush(job(trip, UUID.randomUUID().toString()));
        jdbcTemplate.update("UPDATE story_generation_jobs SET status = ? WHERE generation_id = ?",
                status.name(), previous.getId());
        entityManager.clear();

        StoryGenerationJob next = jobs.saveAndFlush(job(trip, UUID.randomUUID().toString()));

        assertThat(next.getId()).isNotEqualTo(previous.getId());
        assertThat(jobs.findById(previous.getId()).orElseThrow().getStatus()).isEqualTo(status);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT active_trip_id FROM story_generation_jobs WHERE generation_id = ?",
                Long.class, previous.getId())).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT active_trip_id FROM story_generation_jobs WHERE generation_id = ?",
                Long.class, next.getId())).isEqualTo(trip.getId());
    }

    @Test
    void 다른_여행에서도_동일한_AI_실행_ID의_재사용을_거부한다() {
        String executionId = UUID.randomUUID().toString();
        jobs.saveAndFlush(job(trip, executionId));
        Trip otherTrip = otherTrip();

        assertThatThrownBy(() -> jobs.saveAndFlush(job(otherTrip, executionId)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_story_generation_execution");
    }

    @Test
    void 같은_작업에_같은_폴더를_중복_저장할_수_없다() {
        StoryGenerationJob job = jobs.saveAndFlush(job(trip, UUID.randomUUID().toString()));
        TripDetailPlace place = place(trip, 1);
        places.saveAndFlush(new StoryGenerationPlace(job.getId(), place.getId(), 1));

        assertThatThrownBy(() -> places.saveAndFlush(
                new StoryGenerationPlace(job.getId(), place.getId(), 2)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_story_generation_place");
    }

    @Test
    void 같은_작업에_선택_순서를_중복_저장할_수_없다() {
        StoryGenerationJob job = jobs.saveAndFlush(job(trip, UUID.randomUUID().toString()));
        TripDetailPlace first = place(trip, 1);
        TripDetailPlace second = place(trip, 2);
        places.saveAndFlush(new StoryGenerationPlace(job.getId(), first.getId(), 1));

        assertThatThrownBy(() -> places.saveAndFlush(
                new StoryGenerationPlace(job.getId(), second.getId(), 1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_story_generation_place_order");
    }

    private StoryGenerationJob job(Trip target, String executionId) {
        return new StoryGenerationJob(executionId, target.getId(), owner.getUserId(), Story.Mood.PLAIN);
    }

    private Trip otherTrip() {
        return trips.save(new Trip(owner.getUserId(), "다른여행",
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11)));
    }

    private TripDetailPlace place(Trip target, int order) {
        LocalDateTime takenAt = LocalDateTime.of(2026, 10, 10, 12, 0);
        TripDetailPlace place = TripDetailPlace.fromAnalysis(target.getId(), order,
                "장소" + order, BigDecimal.ZERO, BigDecimal.ZERO, takenAt, takenAt, null);
        entityManager.persist(place);
        return place;
    }
}
