package com.yeodam.yeodambe.story.controller;

import com.yeodam.yeodambe.common.exception.*;
import com.yeodam.yeodambe.common.logging.RequestIdFilter;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.service.StoryGenerationAcceptanceService;
import com.yeodam.yeodambe.story.service.StoryGenerationStatusService;
import com.yeodam.yeodambe.story.service.StoryGenerationCancelService;
import com.yeodam.yeodambe.story.service.StoryGenerationTerminalService;
import com.yeodam.yeodambe.story.client.StoryGenerationAiClient;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.exception.StoryGenerationAiRequestException;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.service.request.StoryGenerationRequest;
import com.yeodam.yeodambe.user.security.SecurityConfig;
import com.yeodam.yeodambe.user.security.csrf.*;
import com.yeodam.yeodambe.user.security.jwt.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StoryGenerationController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtConfig.class, AccessTokenIssuer.class,
        CookieAccessTokenResolver.class, ApiAuthenticationEntryPoint.class,
        CsrfAccessDeniedHandler.class, RedisCsrfTokenRepository.class, RequestIdFilter.class,
        StoryGenerationStatusService.class, StoryGenerationCancelService.class, StoryGenerationTerminalService.class})
class StoryGenerationControllerTest {
    @Autowired private MockMvc mvc;
    @Autowired private AccessTokenIssuer issuer;
    @MockitoBean private StoryGenerationAcceptanceService acceptance;
    @MockitoBean private StoryGenerationJobRepository jobs;
    @MockitoBean private StoryGenerationAiClient ai;
    @MockitoBean private ActiveLoginSessionValidator sessionValidator;
    @MockitoBean private CsrfTokenStore csrfStore;
    @MockitoBean private CsrfTokenGenerator csrfGenerator;
    private final StoryGenerationRequest input = new StoryGenerationRequest(Story.Mood.EMOTIONAL, List.of(10L));

    @BeforeEach
    void allowAuthenticationAndCsrf() {
        when(sessionValidator.validate(any(Jwt.class))).thenReturn(OAuth2TokenValidatorResult.success());
        when(csrfStore.find("browser")).thenReturn("csrf-token");
    }

    @Test
    void acceptsCommittedJobWithoutWaitingForKafkaAndPreservesRequestId() throws Exception {
        prepareJob("request-original");

        mvc.perform(authenticated().header("X-Request-ID", "request-original"))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-Request-ID", "request-original"))
                .andExpect(content().json("""
                        {"message":"STORY_GENERATION_ACCEPTED",
                         "data":{"generationId":7,"tripId":100,"status":"QUEUED"}}
                        """));
        verify(acceptance).accept(42L, 100L, input, "request-original");
    }

    @Test
    void generatedRequestIdIsPassedToAcceptanceWhenHeaderIsAbsent() throws Exception {
        var job = job();
        when(acceptance.accept(eq(42L), eq(100L), eq(input), anyString())).thenReturn(job);

        var started = mvc.perform(authenticated()).andExpect(status().isAccepted()).andReturn();
        String requestId = started.getResponse().getHeader("X-Request-ID");
        assertThat(requestId).isNotBlank();
        verify(acceptance).accept(42L, 100L, input, requestId);
    }

    @Test
    void missingAuthenticationIsRejectedBeforeAcceptance() throws Exception {
        mvc.perform(base().cookie(new Cookie("CSRF_CONTEXT", "browser"))
                .header("X-CSRF-TOKEN", "csrf-token")).andExpect(status().isUnauthorized());
        verifyNoInteractions(acceptance);
    }

    @Test
    void missingCsrfIsRejectedBeforeAcceptance() throws Exception {
        mvc.perform(base().cookie(new Cookie("accessToken", issuer.issue(42L, "session")),
                        new Cookie("CSRF_CONTEXT", "browser")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("CSRF_TOKEN_INVALID"));
        verifyNoInteractions(acceptance);
    }

    @Test
    void databaseAcceptanceFailurePreservesUnavailableResponse() throws Exception {
        when(acceptance.accept(eq(42L), eq(100L), eq(input), anyString()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("DB unavailable"));
        mvc.perform(authenticated()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("AUTH_STORE_UNAVAILABLE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"mood\":\"UNKNOWN\",\"tripPlaceIds\":[10]}",
            "",
            "{",
            "{\"mood\":\"EMOTIONAL\",\"tripPlaceIds\":\"not-a-list\"}"
    })
    void malformedRequestReturnsStoryContractErrorBeforeAcceptance(String body) throws Exception {
        mvc.perform(authenticated().content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_STORY_REQUEST"));
        verifyNoInteractions(acceptance);
    }

    @ParameterizedTest
    @CsvSource({"invalid,400,INVALID_STORY_REQUEST", "missing,404,TRIP_NOT_FOUND",
            "forbidden,403,STORY_GENERATION_FORBIDDEN", "active,409,STORY_GENERATION_IN_PROGRESS"})
    void acceptanceRejectionPreservesContractError(String kind, int code, String message) throws Exception {
        RuntimeException failure = switch (kind) {
            case "invalid" -> new InvalidStoryRequestException();
            case "missing" -> new TripNotFoundException();
            case "forbidden" -> new StoryGenerationForbiddenException();
            default -> new StoryGenerationInProgressException();
        };
        when(acceptance.accept(42L, 100L, input, "request-original")).thenThrow(failure);
        mvc.perform(authenticated().header("X-Request-ID", "request-original"))
                .andExpect(status().is(code)).andExpect(jsonPath("$.message").value(message));
    }

    @ParameterizedTest
    @EnumSource(StoryGenerationJob.Status.class)
    void statusReadUsesJwtOwnerAndReturnsStoredStateWithoutCsrf(StoryGenerationJob.Status state) throws Exception {
        var saved = mock(StoryGenerationJob.class);
        when(saved.getId()).thenReturn(7L);
        when(saved.getUserId()).thenReturn(42L);
        when(saved.getStatus()).thenReturn(state);
        when(saved.getStoryId()).thenReturn(state == StoryGenerationJob.Status.COMPLETED ? 99L : null);
        when(jobs.findById(7L)).thenReturn(Optional.of(saved));

        var result = mvc.perform(statusRequest().cookie(new Cookie("accessToken", issuer.issue(42L, "session"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("STORY_GENERATION_STATUS_FOUND"))
                .andExpect(jsonPath("$.data.generationId").value(7))
                .andExpect(jsonPath("$.data.status").value(state.name()))
                .andExpect(jsonPath("$.data.progress").doesNotHaveJsonPath());
        if (state == StoryGenerationJob.Status.FAILED) {
            result.andExpect(jsonPath("$.data.error.code").value("STORY_GENERATION_FAILED"))
                    .andExpect(jsonPath("$.data.error.message").value("스토리 생성에 실패했습니다."));
        } else {
            result.andExpect(jsonPath("$.data.error").isEmpty());
        }
        if (state == StoryGenerationJob.Status.COMPLETED) {
            result.andExpect(jsonPath("$.data.storyId").value(99));
        } else {
            result.andExpect(jsonPath("$.data.storyId").isEmpty());
        }
        verify(jobs).findById(7L);
        verifyNoInteractions(acceptance);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/story-generations/7", "/story-generation/7"})
    void bothStatusPathsReturnTheSameResponse(String path) throws Exception {
        var saved = mock(StoryGenerationJob.class);
        when(saved.getId()).thenReturn(7L);
        when(saved.getUserId()).thenReturn(42L);
        when(saved.getStatus()).thenReturn(StoryGenerationJob.Status.QUEUED);
        when(jobs.findById(7L)).thenReturn(Optional.of(saved));

        mvc.perform(statusRequest(path).cookie(new Cookie("accessToken", issuer.issue(42L, "session"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("STORY_GENERATION_STATUS_FOUND"))
                .andExpect(jsonPath("$.data.generationId").value(7))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
        verify(jobs).findById(7L);
    }

    @ParameterizedTest
    @CsvSource({
            "true,/story-generation/7", "false,/story-generation/7",
            "true,/story-generations/7", "false,/story-generations/7"
    })
    void missingAndOtherOwnerJobsReturnSameNotFoundResponse(boolean missing, String path) throws Exception {
        var saved = mock(StoryGenerationJob.class);
        if (missing) {
            when(jobs.findById(7L)).thenReturn(Optional.empty());
        } else {
            when(saved.getUserId()).thenReturn(999L);
            when(jobs.findById(7L)).thenReturn(Optional.of(saved));
        }

        mvc.perform(statusRequest(path).cookie(new Cookie("accessToken", issuer.issue(42L, "session"))))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"message\":\"STORY_GENERATION_NOT_FOUND\",\"data\":null}"));
        verifyNoInteractions(acceptance);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/story-generations/7", "/story-generation/7"})
    void unauthenticatedStatusReadStopsBeforeRepositoryLookup(String path) throws Exception {
        mvc.perform(statusRequest(path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(jobs, acceptance);
    }

    private MockHttpServletRequestBuilder statusRequest() {
        return statusRequest("/story-generation/7");
    }

    private MockHttpServletRequestBuilder statusRequest(String path) {
        return get("/api" + path).contextPath("/api").servletPath(path);
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/story-generation/0", "GET,/story-generation/-1",
            "GET,/story-generations/0", "GET,/story-generations/-1",
            "DELETE,/story-generations/0", "DELETE,/story-generations/-1",
            "POST,/trips/0/story-generations", "POST,/trips/-1/story-generations"
    })
    void nonPositiveIdsAreRejectedBeforeBusinessProcessing(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), "/api" + path)
                        .contextPath("/api").servletPath(path)
                        .cookie(new Cookie("accessToken", issuer.issue(42L, "session")),
                                new Cookie("CSRF_CONTEXT", "browser"))
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .contentType("application/json")
                        .content("{\"mood\":\"EMOTIONAL\",\"tripPlaceIds\":[10]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(acceptance, jobs, ai);
    }

    @Test
    void cancellationReturnsNoContentAfterFinishingJob() throws Exception {
        var saved = prepareCancellationJob(42L);
        when(ai.cancel(100L)).thenReturn(new StoryGenerationAiResponse(100L, "execution-cancel",
                StoryGenerationAiResponse.Status.CANCELED, null, null, null, null));

        mvc.perform(authenticatedCancellation()).andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(saved.getStatus()).isEqualTo(StoryGenerationJob.Status.CANCELED);
        verify(ai).cancel(100L);
        verifyNoInteractions(acceptance);
    }

    @ParameterizedTest
    @EnumSource(value = StoryGenerationJob.Status.class, names = {"COMPLETED", "FAILED", "CANCELED"})
    void finishedCancellationReturnsConflictBeforeCallingAi(StoryGenerationJob.Status state) throws Exception {
        var saved = prepareCancellationJob(42L);
        switch (state) {
            case COMPLETED -> saved.complete(99L);
            case FAILED -> saved.fail("WORKER_DEAD");
            case CANCELED -> saved.cancel();
            default -> throw new AssertionError(state);
        }

        mvc.perform(authenticatedCancellation()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("STORY_GENERATION_CANNOT_BE_CANCELED"));
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingOrOtherOwnerCancellationReturnsNotFound(boolean missing) throws Exception {
        if (missing) {
            when(jobs.findById(7L)).thenReturn(Optional.empty());
        } else {
            prepareCancellationJob(999L);
        }

        mvc.perform(authenticatedCancellation()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("STORY_GENERATION_NOT_FOUND"));
        verifyNoInteractions(ai);
    }

    @Test
    void aiTaskNotFoundReturnsNoContentAndStatusLookupShowsCanceled() throws Exception {
        var saved = prepareCancellationJob(42L);
        when(ai.cancel(100L)).thenThrow(new StoryGenerationAiRequestException(
                StoryGenerationInternalErrorMessage.AI_CANCEL_REQUEST_FAILED, 404, "TASK_NOT_FOUND"));

        mvc.perform(authenticatedCancellation()).andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(saved.getStatus()).isEqualTo(StoryGenerationJob.Status.CANCELED);
        mvc.perform(statusRequest().cookie(new Cookie("accessToken", issuer.issue(42L, "session"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
    }

    @Test
    void aiCancellationConflictPreservesContractError() throws Exception {
        prepareCancellationJob(42L);
        when(ai.cancel(100L)).thenThrow(new StoryGenerationAiRequestException(
                StoryGenerationInternalErrorMessage.AI_CANCEL_REQUEST_FAILED, 409, "TASK_NOT_CANCELABLE"));

        mvc.perform(authenticatedCancellation()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("STORY_GENERATION_CANNOT_BE_CANCELED"));
    }

    @Test
    void aiCancellationFailureReturnsInternalErrorInsteadOfNoContent() throws Exception {
        prepareCancellationJob(42L);
        when(ai.cancel(100L)).thenThrow(new StoryGenerationAiRequestException(
                StoryGenerationInternalErrorMessage.AI_CANCEL_REQUEST_FAILED, null, null));

        mvc.perform(authenticatedCancellation()).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("INTERNAL_SERVER_ERROR"));
    }

    @Test
    void unauthenticatedCancellationStopsBeforeLookupAndAiCall() throws Exception {
        mvc.perform(cancellationRequest().cookie(new Cookie("CSRF_CONTEXT", "browser"))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(jobs, ai);
    }

    @Test
    void cancellationWithoutCsrfStopsBeforeLookupAndAiCall() throws Exception {
        mvc.perform(cancellationRequest().cookie(new Cookie("accessToken", issuer.issue(42L, "session")),
                        new Cookie("CSRF_CONTEXT", "browser")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("CSRF_TOKEN_INVALID"));
        verifyNoInteractions(jobs, ai);
    }

    private StoryGenerationJob prepareCancellationJob(Long ownerId) {
        var saved = new StoryGenerationJob("execution-cancel", 100L, ownerId, Story.Mood.EMOTIONAL);
        when(jobs.findById(7L)).thenReturn(Optional.of(saved));
        when(jobs.findByIdForUpdate(7L)).thenReturn(Optional.of(saved));
        return saved;
    }

    private MockHttpServletRequestBuilder authenticatedCancellation() {
        return cancellationRequest().cookie(new Cookie("accessToken", issuer.issue(42L, "session")),
                        new Cookie("CSRF_CONTEXT", "browser"))
                .header("X-CSRF-TOKEN", "csrf-token");
    }

    private MockHttpServletRequestBuilder cancellationRequest() {
        return delete("/api/story-generations/7").contextPath("/api")
                .servletPath("/story-generations/7");
    }

    private void prepareJob(String requestId) {
        var job = job();
        when(acceptance.accept(42L, 100L, input, requestId)).thenReturn(job);
    }

    private StoryGenerationJob job() {
        var job = mock(StoryGenerationJob.class);
        when(job.getId()).thenReturn(7L);
        when(job.getTripId()).thenReturn(100L);
        when(job.getStatus()).thenReturn(StoryGenerationJob.Status.QUEUED);
        return job;
    }

    private MockHttpServletRequestBuilder authenticated() {
        return base().cookie(new Cookie("accessToken", issuer.issue(42L, "session")),
                        new Cookie("CSRF_CONTEXT", "browser"))
                .header("X-CSRF-TOKEN", "csrf-token");
    }

    private MockHttpServletRequestBuilder base() {
        return post("/api/trips/100/story-generations").contextPath("/api")
                .servletPath("/trips/100/story-generations").contentType("application/json")
                .content("{\"mood\":\"EMOTIONAL\",\"tripPlaceIds\":[10]}");
    }
}
