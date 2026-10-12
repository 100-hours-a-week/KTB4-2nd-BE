package com.yeodam.yeodambe.story.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.StoryAlreadyExistsException;
import com.yeodam.yeodambe.common.exception.StoryGenerationInProgressException;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.entity.StoryGenerationPlace;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.repository.StoryGenerationPlaceRepository;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.service.request.StoryGenerationRequest;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StoryGenerationJobServiceTest {
    @Autowired private StoryGenerationJobService service;
    @MockitoSpyBean private StoryGenerationJobRepository jobs;
    @Autowired private StoryGenerationPlaceRepository places;
    @Autowired private StoryRepository stories;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripDetailPlaceRepository folders;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private StoryGenerationInputService inputService;

    private User owner;
    private Trip trip;
    private TripDetailPlace first;
    private TripDetailPlace second;
    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void prepare() {
        serviceLogger = (Logger) LoggerFactory.getLogger(StoryGenerationJobService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            owner = users.save(new User(UUID.randomUUID() + "@yeodam.test", "스토리회원"));
            trip = trips.save(new Trip(owner.getUserId(), "스토리여행",
                    LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11)));
            first = folder(1);
            second = folder(2);
        });
    }

    @AfterEach
    void cleanUp() {
        serviceLogger.detachAppender(logAppender);
        logAppender.stop();
        jdbcTemplate.update("DELETE FROM story_generation_places WHERE generation_id IN "
                + "(SELECT generation_id FROM story_generation_jobs WHERE trip_id = ?)", trip.getId());
        jdbcTemplate.update("DELETE FROM story_generation_jobs WHERE trip_id = ?", trip.getId());
        jdbcTemplate.update("UPDATE trips SET current_story_id = NULL WHERE trip_id = ?", trip.getId());
        jdbcTemplate.update("DELETE FROM stories WHERE trip_id = ?", trip.getId());
        jdbcTemplate.update("DELETE FROM trip_detail_places WHERE trip_id = ?", trip.getId());
        jdbcTemplate.update("DELETE FROM trips WHERE trip_id = ?", trip.getId());
        jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", owner.getUserId());
    }

    @Test
    void 작업과_선택폴더가_커밋되어_입력순서로_조회된다() {
        StoryGenerationRequest request = request(List.of(second.getId(), first.getId()));
        when(inputService.validate(owner.getUserId(), trip.getId(), request)).thenReturn(trip);

        StoryGenerationJob result = service.create(owner.getUserId(), trip.getId(), request);

        StoryGenerationJob saved = jobs.findById(result.getId()).orElseThrow();
        assertThat(saved.getTripId()).isEqualTo(trip.getId());
        assertThat(saved.getUserId()).isEqualTo(owner.getUserId());
        assertThat(saved.getMood()).isEqualTo(Story.Mood.EMOTIONAL);
        assertThat(saved.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
        assertThat(saved.getProgress()).isZero();
        assertThat(UUID.fromString(saved.getExecutionId()).toString()).isEqualTo(saved.getExecutionId());
        var selected = places.findAllByGenerationIdOrderByOrderNumberAsc(saved.getId());
        assertThat(selected).extracting(StoryGenerationPlace::getTripPlaceId)
                .containsExactly(second.getId(), first.getId());
        assertThat(selected).extracting(StoryGenerationPlace::getOrderNumber).containsExactly(1, 2);
    }

    @Test
    void 현재_완료된_스토리가_있으면_작업을_저장하지_않는다() {
        Story story = stories.saveAndFlush(new Story(trip.getId(), Story.Mood.PLAIN, "완성된 이야기"));
        jdbcTemplate.update("UPDATE stories SET processing_status = 'COMPLETED' WHERE story_id = ?",
                story.getId());
        jdbcTemplate.update("UPDATE trips SET current_story_id = ? WHERE trip_id = ?",
                story.getId(), trip.getId());
        StoryGenerationRequest request = request(List.of(first.getId()));
        when(inputService.validate(owner.getUserId(), trip.getId(), request)).thenReturn(trip);

        assertThatThrownBy(() -> service.create(owner.getUserId(), trip.getId(), request))
                .isInstanceOf(StoryAlreadyExistsException.class);

        assertThat(jobCount()).isZero();
        assertThat(stories.findCurrentCompletedByTripId(trip.getId())).isPresent();
    }

    @Test
    void 선택폴더_저장에_실패하면_먼저_저장한_작업도_롤백된다() {
        StoryGenerationRequest request = request(List.of(first.getId(), -1L));
        when(inputService.validate(owner.getUserId(), trip.getId(), request)).thenReturn(trip);

        assertThatThrownBy(() -> service.create(owner.getUserId(), trip.getId(), request))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jobCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM story_generation_places WHERE trip_place_id = ?
                """, Integer.class, first.getId())).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = StoryGenerationJob.Status.class, names = {"QUEUED", "PROCESSING"})
    void 진행중인_작업이_있으면_사전조회에서_거부하고_기존작업을_보존한다(
            StoryGenerationJob.Status status
    ) {
        StoryGenerationJob previous = jobs.saveAndFlush(new StoryGenerationJob(
                UUID.randomUUID().toString(), trip.getId(), owner.getUserId(), Story.Mood.PLAIN));
        jdbcTemplate.update("UPDATE story_generation_jobs SET status = ? WHERE generation_id = ?",
                status.name(), previous.getId());
        StoryGenerationRequest request = request(List.of(first.getId()));
        when(inputService.validate(owner.getUserId(), trip.getId(), request)).thenReturn(trip);

        assertThatThrownBy(() -> service.create(owner.getUserId(), trip.getId(), request))
                .isInstanceOf(StoryGenerationInProgressException.class)
                .hasCause(null);

        assertThat(jobCount()).isEqualTo(1);
        assertThat(jobs.findById(previous.getId()).orElseThrow().getStatus()).isEqualTo(status);
        assertThat(places.findAllByGenerationIdOrderByOrderNumberAsc(previous.getId())).isEmpty();
        assertRejectedLog("active_job_check", false);
    }

    @Test
    void 사전조회가_중복을_놓쳐도_실제_DB_충돌을_생성중_예외로_변환한다() {
        StoryGenerationJob previous = jobs.saveAndFlush(new StoryGenerationJob(
                UUID.randomUUID().toString(), trip.getId(), owner.getUserId(), Story.Mood.PLAIN));
        doReturn(false).when(jobs).existsByTripIdAndStatusIn(trip.getId(),
                List.of(StoryGenerationJob.Status.QUEUED, StoryGenerationJob.Status.PROCESSING));
        StoryGenerationRequest request = request(List.of(first.getId()));
        when(inputService.validate(owner.getUserId(), trip.getId(), request)).thenReturn(trip);

        assertThatThrownBy(() -> service.create(owner.getUserId(), trip.getId(), request))
                .isInstanceOf(StoryGenerationInProgressException.class)
                .hasCauseInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_story_generation_active_trip");

        assertThat(jobCount()).isEqualTo(1);
        assertThat(jobs.findById(previous.getId())).isPresent();
        assertThat(places.findAllByGenerationIdOrderByOrderNumberAsc(previous.getId())).isEmpty();
        assertRejectedLog("active_job_unique", true);
    }

    @Test
    void 작업의_회원_외래키_오류를_생성중_오류로_바꾸지_않는다() {
        StoryGenerationRequest request = request(List.of(first.getId()));
        when(inputService.validate(-1L, trip.getId(), request)).thenReturn(trip);

        assertThatThrownBy(() -> service.create(-1L, trip.getId(), request))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("fk_story_generation_user");

        assertThat(jobCount()).isZero();
        assertThat(logAppender.list).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = StoryGenerationJob.Status.class, names = {"COMPLETED", "FAILED", "CANCELED"})
    void 종료된_작업_이력만_있으면_새작업을_저장할_수_있다(StoryGenerationJob.Status status) {
        StoryGenerationJob previous = jobs.saveAndFlush(new StoryGenerationJob(
                UUID.randomUUID().toString(), trip.getId(), owner.getUserId(), Story.Mood.PLAIN));
        jdbcTemplate.update("UPDATE story_generation_jobs SET status = ? WHERE generation_id = ?",
                status.name(), previous.getId());
        StoryGenerationRequest request = request(List.of(first.getId()));
        when(inputService.validate(owner.getUserId(), trip.getId(), request)).thenReturn(trip);

        StoryGenerationJob next = service.create(owner.getUserId(), trip.getId(), request);

        assertThat(jobCount()).isEqualTo(2);
        assertThat(next.getId()).isNotEqualTo(previous.getId());
        assertThat(jobs.findById(previous.getId()).orElseThrow().getStatus()).isEqualTo(status);
        assertThat(next.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
    }

    private void assertRejectedLog(String stage, boolean hasDatabaseCause) {
        assertThat(logAppender.list).hasSize(1);
        ILoggingEvent event = logAppender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(keyValue(event, "event")).isEqualTo("story_generation_rejected");
        assertThat(keyValue(event, "result")).isEqualTo("rejected");
        assertThat(keyValue(event, "trip_id")).isEqualTo(trip.getId());
        assertThat(keyValue(event, "error_code")).isEqualTo("STORY_GENERATION_IN_PROGRESS");
        assertThat(keyValue(event, "failure_stage")).isEqualTo(stage);
        if (hasDatabaseCause) {
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName())
                    .isEqualTo(DataIntegrityViolationException.class.getName());
        } else {
            assertThat(event.getThrowableProxy()).isNull();
        }
    }

    private Object keyValue(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> key.equals(pair.key))
                .map(pair -> pair.value)
                .findFirst()
                .orElse(null);
    }

    private int jobCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM story_generation_jobs WHERE trip_id = ?", Integer.class, trip.getId());
    }

    private StoryGenerationRequest request(List<Long> folderIds) {
        return new StoryGenerationRequest(Story.Mood.EMOTIONAL, folderIds);
    }

    private TripDetailPlace folder(int order) {
        LocalDateTime takenAt = LocalDateTime.of(2026, 10, 10, 12, 0);
        return folders.save(TripDetailPlace.fromAnalysis(trip.getId(), order, "장소" + order,
                BigDecimal.ZERO, BigDecimal.ZERO, takenAt, takenAt, null));
    }
}
