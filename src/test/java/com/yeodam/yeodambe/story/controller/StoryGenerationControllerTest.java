package com.yeodam.yeodambe.story.controller;

import com.yeodam.yeodambe.common.exception.*;
import com.yeodam.yeodambe.common.logging.RequestIdFilter;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.service.StoryGenerationAcceptanceService;
import com.yeodam.yeodambe.story.service.StoryGenerationPublicationService;
import com.yeodam.yeodambe.story.service.request.StoryGenerationRequest;
import com.yeodam.yeodambe.user.security.SecurityConfig;
import com.yeodam.yeodambe.user.security.csrf.*;
import com.yeodam.yeodambe.user.security.jwt.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StoryGenerationController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtConfig.class, AccessTokenIssuer.class,
        CookieAccessTokenResolver.class, ApiAuthenticationEntryPoint.class,
        CsrfAccessDeniedHandler.class, RedisCsrfTokenRepository.class, RequestIdFilter.class})
class StoryGenerationControllerTest {
    @Autowired private MockMvc mvc;
    @Autowired private AccessTokenIssuer issuer;
    @MockitoBean private StoryGenerationAcceptanceService acceptance;
    @MockitoBean private StoryGenerationPublicationService publication;
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
    void waitsForPublicationAndReturnsContractResponseWithOriginalRequestId() throws Exception {
        prepareJob("request-original");
        var pending = new CompletableFuture<Void>();
        when(publication.publish(7L)).thenReturn(pending);

        var started = mvc.perform(authenticated().header("X-Request-ID", "request-original"))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(pending.isDone()).isFalse();
        pending.complete(null);

        mvc.perform(asyncDispatch(started)).andExpect(status().isAccepted())
                .andExpect(header().string("X-Request-ID", "request-original"))
                .andExpect(content().json("""
                        {"message":"STORY_GENERATION_ACCEPTED",
                         "data":{"generationId":7,"tripId":100,"status":"QUEUED"}}
                        """));
        verify(acceptance).accept(42L, 100L, input, "request-original");
        verify(publication).publish(7L);
    }

    @Test
    void generatedRequestIdIsPassedToAcceptanceWhenHeaderIsAbsent() throws Exception {
        var job = job();
        when(acceptance.accept(eq(42L), eq(100L), eq(input), anyString())).thenReturn(job);
        when(publication.publish(7L)).thenReturn(CompletableFuture.completedFuture(null));

        var started = mvc.perform(authenticated()).andExpect(request().asyncStarted()).andReturn();
        String requestId = started.getResponse().getHeader("X-Request-ID");
        assertThat(requestId).isNotBlank();
        mvc.perform(asyncDispatch(started)).andExpect(status().isAccepted());
        verify(acceptance).accept(42L, 100L, input, requestId);
    }

    @Test
    void missingAuthenticationIsRejectedBeforeAcceptance() throws Exception {
        mvc.perform(base().cookie(new Cookie("CSRF_CONTEXT", "browser"))
                .header("X-CSRF-TOKEN", "csrf-token")).andExpect(status().isUnauthorized());
        verifyNoInteractions(acceptance, publication);
    }

    @Test
    void missingCsrfIsRejectedBeforeAcceptance() throws Exception {
        mvc.perform(base().cookie(new Cookie("accessToken", issuer.issue(42L, "session")),
                        new Cookie("CSRF_CONTEXT", "browser")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("CSRF_TOKEN_INVALID"));
        verifyNoInteractions(acceptance, publication);
    }

    @Test
    void publicationFailureReturnsInternalErrorInsteadOfAcceptedResponse() throws Exception {
        prepareJob("request-original");
        when(publication.publish(7L)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        var started = mvc.perform(authenticated().header("X-Request-ID", "request-original"))
                .andExpect(request().asyncStarted()).andReturn();

        mvc.perform(asyncDispatch(started)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("INTERNAL_SERVER_ERROR"));
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
        verifyNoInteractions(acceptance, publication);
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
        verifyNoInteractions(publication);
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
