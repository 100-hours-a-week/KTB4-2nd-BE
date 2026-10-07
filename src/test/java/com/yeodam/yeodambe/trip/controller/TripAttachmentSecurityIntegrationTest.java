package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.trip.service.TripAttachmentDetailService;
import com.yeodam.yeodambe.trip.service.TripAttachmentDeletionService;
import com.yeodam.yeodambe.trip.service.TripAttachmentDownloadService;
import com.yeodam.yeodambe.trip.service.BulkAttachmentDownloadService;
import com.yeodam.yeodambe.trip.service.TripAttachmentListService;
import com.yeodam.yeodambe.trip.service.TripAttachmentService;
import com.yeodam.yeodambe.trip.service.InitialAttachmentUploadUrlService;
import com.yeodam.yeodambe.trip.service.AdditionalAttachmentUploadUrlService;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.service.response.AdditionalAttachmentUploadUrlResponse;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadUrlResponse;
import org.springframework.http.MediaType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import com.yeodam.yeodambe.user.security.SecurityConfig;
import com.yeodam.yeodambe.user.security.csrf.CsrfAccessDeniedHandler;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenGenerator;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.csrf.RedisCsrfTokenRepository;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.jwt.ActiveLoginSessionValidator;
import com.yeodam.yeodambe.user.security.jwt.ApiAuthenticationEntryPoint;
import com.yeodam.yeodambe.user.security.jwt.CookieAccessTokenResolver;
import com.yeodam.yeodambe.user.security.jwt.JwtConfig;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TripAttachmentController.class)
@ActiveProfiles("test")
@Import({
        SecurityConfig.class,
        JwtConfig.class,
        AccessTokenIssuer.class,
        CookieAccessTokenResolver.class,
        ApiAuthenticationEntryPoint.class,
        CsrfAccessDeniedHandler.class,
        RedisCsrfTokenRepository.class
})
class TripAttachmentSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessTokenIssuer accessTokenIssuer;

    @MockitoBean
    private TripAttachmentService tripAttachmentService;

    @MockitoBean
    private TripAttachmentListService tripAttachmentListService;

    @MockitoBean
    private TripAttachmentDetailService tripAttachmentDetailService;

    @MockitoBean
    private TripAttachmentDeletionService tripAttachmentDeletionService;

    @MockitoBean
    private TripAttachmentDownloadService tripAttachmentDownloadService;

    @MockitoBean
    private BulkAttachmentDownloadService bulkAttachmentDownloadService;

    @MockitoBean
    private InitialAttachmentUploadUrlService uploadUrlService;

    @MockitoBean
    private AdditionalAttachmentUploadUrlService additionalUploadUrlService;

    @MockitoBean
    private com.yeodam.yeodambe.trip.service.InitialAttachmentUploadCompletionService uploadCompletion;

    @MockitoBean
    private ActiveLoginSessionValidator activeLoginSessionValidator;

    @MockitoBean
    private CsrfTokenStore csrfTokenStore;

    @MockitoBean
    private CsrfTokenGenerator csrfTokenGenerator;

    @BeforeEach
    void allowSessionValidation() {
        given(activeLoginSessionValidator.validate(any(Jwt.class)))
                .willReturn(OAuth2TokenValidatorResult.success());
    }

    @Test
    void 추가_업로드_URL은_JSON과_JWT_사용자_ID로_요청하고_명세_응답을_반환한다() throws Exception {
        String token = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("upload-browser")).willReturn("csrf-token");
        var request = new AdditionalAttachmentUploadUrlRequest(
                "550e8400-e29b-41d4-a716-446655440000", 1, 1, true,
                List.of(new AdditionalAttachmentUploadUrlRequest.Attachment("photo.jpg", "image/jpeg", 1024L)));
        given(additionalUploadUrlService.issueUploadUrls(7L, 42L, request)).willReturn(
                new AdditionalAttachmentUploadUrlResponse("upload-id", List.of(
                        new AdditionalAttachmentUploadUrlResponse.Attachment(
                                "photo.jpg", "https://example.test/additional-upload", "PUT",
                                Map.of("Content-Type", "image/jpeg", "If-None-Match", "*"),
                                OffsetDateTime.parse("2026-10-07T09:10:00Z")))));

        mockMvc.perform(post("/api/trips/7/attachments/upload-urls")
                        .contextPath("/api").servletPath("/trips/7/attachments/upload-urls")
                        .contentType(MediaType.APPLICATION_JSON).content(additionalUploadUrlBody())
                        .cookie(new Cookie("accessToken", token), new Cookie("CSRF_CONTEXT", "upload-browser"))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_ATTACHMENT_UPLOAD_URLS_ISSUED"))
                .andExpect(jsonPath("$.data.uploadId").value("upload-id"))
                .andExpect(jsonPath("$.data.attachments[0].fileName").value("photo.jpg"))
                .andExpect(jsonPath("$.data.attachments[0].uploadUrl").value("https://example.test/additional-upload"))
                .andExpect(jsonPath("$.data.attachments[0].method").value("PUT"))
                .andExpect(jsonPath("$.data.attachments[0].headers['Content-Type']").value("image/jpeg"))
                .andExpect(jsonPath("$.data.attachments[0].headers['If-None-Match']").value("*"))
                .andExpect(jsonPath("$.data.attachments[0].expiresAt").isNotEmpty());
        then(additionalUploadUrlService).should().issueUploadUrls(7L, 42L, request);
    }

    @Test
    void 추가_업로드_URL_요청에_인증이_없으면_401이며_서비스를_호출하지_않는다() throws Exception {
        given(csrfTokenStore.find("upload-browser")).willReturn("csrf-token");
        mockMvc.perform(post("/api/trips/7/attachments/upload-urls")
                        .contextPath("/api").servletPath("/trips/7/attachments/upload-urls")
                        .contentType(MediaType.APPLICATION_JSON).content(additionalUploadUrlBody())
                        .cookie(new Cookie("CSRF_CONTEXT", "upload-browser"))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));
        then(additionalUploadUrlService).shouldHaveNoInteractions();
    }

    @Test
    void 추가_업로드_URL_요청의_CSRF가_틀리면_403이며_서비스를_호출하지_않는다() throws Exception {
        given(csrfTokenStore.find("upload-browser")).willReturn("csrf-token");
        mockMvc.perform(post("/api/trips/7/attachments/upload-urls")
                        .contextPath("/api").servletPath("/trips/7/attachments/upload-urls")
                        .contentType(MediaType.APPLICATION_JSON).content(additionalUploadUrlBody())
                        .cookie(new Cookie("accessToken", accessTokenIssuer.issue(42L, "sid-42")),
                                new Cookie("CSRF_CONTEXT", "upload-browser"))
                        .header("X-CSRF-TOKEN", "wrong-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("CSRF_TOKEN_INVALID"));
        then(additionalUploadUrlService).shouldHaveNoInteractions();
    }

    @Test
    void 추가_업로드_URL_접수가_허용되지_않으면_409를_반환한다() throws Exception {
        given(csrfTokenStore.find("upload-browser")).willReturn("csrf-token");
        given(additionalUploadUrlService.issueUploadUrls(eq(7L), eq(42L), any()))
                .willThrow(new com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException());
        mockMvc.perform(post("/api/trips/7/attachments/upload-urls")
                        .contextPath("/api").servletPath("/trips/7/attachments/upload-urls")
                        .contentType(MediaType.APPLICATION_JSON).content(additionalUploadUrlBody())
                        .cookie(new Cookie("accessToken", accessTokenIssuer.issue(42L, "sid-42")),
                                new Cookie("CSRF_CONTEXT", "upload-browser"))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("TRIP_ATTACHMENT_ADD_NOT_ALLOWED"));
    }

    private String additionalUploadUrlBody() {
        return """
                {"additionId":"550e8400-e29b-41d4-a716-446655440000",
                 "batchNo":1,"totalAttachmentCount":1,"complete":true,
                 "attachments":[{"fileName":"photo.jpg","contentType":"image/jpeg","sizeBytes":1024}]}
                """;
    }

    @Test
    void 인증된_회원의_파일_정보로_업로드_URL_응답을_반환한다() throws Exception {
        String token = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("upload-browser")).willReturn("csrf-token");
        var request = new InitialAttachmentUploadUrlRequest(1, 1, true,
                List.of(new InitialAttachmentUploadUrlRequest.Attachment("photo.jpg", "image/jpeg", 1024L)));
        given(uploadUrlService.issueUploadUrls(7L, 42L, request)).willReturn(
                new InitialAttachmentUploadUrlResponse("upload-id", List.of(
                        new InitialAttachmentUploadUrlResponse.Attachment(
                                "photo.jpg", "https://example.test/upload", "PUT",
                                Map.of("Content-Type", "image/jpeg", "If-None-Match", "*"),
                                OffsetDateTime.parse("2026-10-01T09:10:00Z")))));

        mockMvc.perform(post("/api/trips/7/initial-attachments/upload-urls")
                        .contextPath("/api").servletPath("/trips/7/initial-attachments/upload-urls")
                        .contentType(MediaType.APPLICATION_JSON).content(uploadUrlBody())
                        .cookie(new Cookie("accessToken", token), new Cookie("CSRF_CONTEXT", "upload-browser"))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_INITIAL_ATTACHMENT_UPLOAD_URLS_ISSUED"))
                .andExpect(jsonPath("$.data.uploadId").value("upload-id"))
                .andExpect(jsonPath("$.data.attachments[0].fileName").value("photo.jpg"))
                .andExpect(jsonPath("$.data.attachments[0].uploadUrl").value("https://example.test/upload"))
                .andExpect(jsonPath("$.data.attachments[0].method").value("PUT"))
                .andExpect(jsonPath("$.data.attachments[0].headers['Content-Type']").value("image/jpeg"))
                .andExpect(jsonPath("$.data.attachments[0].headers['If-None-Match']").value("*"))
                .andExpect(jsonPath("$.data.attachments[0].expiresAt").isNotEmpty());

        then(uploadUrlService).should().issueUploadUrls(7L, 42L, request);
    }

    @Test
    void 업로드_URL_요청의_CSRF가_틀리면_서비스_호출_전에_403을_반환한다() throws Exception {
        String token = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("upload-browser")).willReturn("csrf-token");

        mockMvc.perform(post("/trips/7/initial-attachments/upload-urls")
                        .contentType(MediaType.APPLICATION_JSON).content(uploadUrlBody())
                        .cookie(new Cookie("accessToken", token), new Cookie("CSRF_CONTEXT", "upload-browser"))
                        .header("X-CSRF-TOKEN", "wrong-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("CSRF_TOKEN_INVALID"));

        then(uploadUrlService).shouldHaveNoInteractions();
    }

    @Test
    void 업로드_URL_요청에_인증_쿠키가_없으면_401을_반환한다() throws Exception {
        given(csrfTokenStore.find("upload-browser")).willReturn("csrf-token");

        mockMvc.perform(post("/trips/7/initial-attachments/upload-urls")
                        .contentType(MediaType.APPLICATION_JSON).content(uploadUrlBody())
                        .cookie(new Cookie("CSRF_CONTEXT", "upload-browser"))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));

        then(uploadUrlService).shouldHaveNoInteractions();
    }

    @Test
    void JSON_중간_배치_완료는_인증_사용자_ID로_처리하고_204_빈_본문을_반환한다() throws Exception {
        String token = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("upload-browser")).willReturn("csrf-token");
        var request = new com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadCompleteRequest("upload-id");
        given(uploadCompletion.complete(7L, 42L, request)).willReturn(Optional.empty());
        mockMvc.perform(post("/api/trips/7/initial-attachments").contextPath("/api")
                        .servletPath("/trips/7/initial-attachments")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"uploadId\":\"upload-id\"}")
                        .cookie(new Cookie("accessToken", token), new Cookie("CSRF_CONTEXT", "upload-browser"))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        then(uploadCompletion).should().complete(7L, 42L, request);
    }

    @Test
    void JSON_마지막_배치_완료는_기존_200_응답_구조를_유지한다() throws Exception {
        String token = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("upload-browser")).willReturn("csrf-token");
        given(uploadCompletion.complete(eq(7L), eq(42L), any())).willReturn(Optional.of(
                new TripProcessingStatusResponse(7L, TripProcessingStatusResponse.Status.COMPLETED,
                        new TripProcessingStatusResponse.Progress(2, 2), null,
                        new TripProcessingStatusResponse.Result(7L, 1, 2, 0), null)));
        mockMvc.perform(post("/trips/7/initial-attachments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"upload-id\"}")
                        .cookie(new Cookie("accessToken", token), new Cookie("CSRF_CONTEXT", "upload-browser"))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_PROCESSING_STATUS_FOUND"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.result.classifiedAttachmentCount").value(2));
    }

    @Test
    void JSON_완료_통지의_인증과_CSRF를_모두_검증한다() throws Exception {
        given(csrfTokenStore.find("upload-browser")).willReturn("csrf-token");
        mockMvc.perform(post("/trips/7/initial-attachments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"upload-id\"}")
                        .cookie(new Cookie("CSRF_CONTEXT", "upload-browser"))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/trips/7/initial-attachments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"upload-id\"}")
                        .cookie(new Cookie("accessToken", accessTokenIssuer.issue(42L, "sid-42")),
                                new Cookie("CSRF_CONTEXT", "upload-browser"))
                        .header("X-CSRF-TOKEN", "wrong-token"))
                .andExpect(status().isForbidden());
        then(uploadCompletion).shouldHaveNoInteractions();
    }

    private String uploadUrlBody() {
        return """
                {"batchNo":1,"totalAttachmentCount":1,"complete":true,
                 "attachments":[{"fileName":"photo.jpg","contentType":"image/jpeg","sizeBytes":1024}]}
                """;
    }

    @Test
    void 유효한_쿠키_Jwt와_CSRF로_첨부_서비스를_호출한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("attachment-browser")).willReturn("csrf-token");
        given(tripAttachmentService.uploadInitialAttachments(
                eq(7L), eq(42L), anyList(), eq(1), eq(1), eq(true)))
                .willReturn(Optional.of(completed()));

                mockMvc.perform(multipart("/trips/7/initial-attachments")
                        .file("attachments[]", jpeg())
                        .param("batchNo", "1")
                        .param("totalAttachmentCount", "1")
                        .param("complete", "true")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", "attachment-browser")
                        )
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_PROCESSING_STATUS_FOUND"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        then(tripAttachmentService).should()
                .uploadInitialAttachments(eq(7L), eq(42L), anyList(), eq(1), eq(1), eq(true));
    }

    @Test
    void CSRF가_일치하지_않으면_403이고_첨부_서비스를_호출하지_않는다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("invalid-attachment-browser")).willReturn("csrf-token");

                mockMvc.perform(multipart("/trips/7/initial-attachments")
                        .file("attachments[]", jpeg())
                        .param("batchNo", "1")
                        .param("totalAttachmentCount", "1")
                        .param("complete", "true")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", "invalid-attachment-browser")
                        )
                        .header("X-CSRF-TOKEN", "wrong-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("CSRF_TOKEN_INVALID"));

        then(tripAttachmentService).should(never())
                .uploadInitialAttachments(any(), any(), anyList(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void 인증_쿠키가_없으면_401이고_첨부_서비스를_호출하지_않는다() throws Exception {
        given(csrfTokenStore.find("unauthorized-attachment-browser")).willReturn("csrf-token");

                mockMvc.perform(multipart("/trips/7/initial-attachments")
                        .file("attachments[]", jpeg())
                        .param("batchNo", "1")
                        .param("totalAttachmentCount", "1")
                        .param("complete", "true")
                        .cookie(new Cookie("CSRF_CONTEXT", "unauthorized-attachment-browser"))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));

        then(tripAttachmentService).should(never())
                .uploadInitialAttachments(any(), any(), anyList(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void 유효한_쿠키_Jwt와_CSRF로_첨부를_삭제한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("attachment-delete-browser")).willReturn("csrf-token");

        mockMvc.perform(delete("/attachments/11")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", "attachment-delete-browser")
                        )
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isNoContent());

        then(tripAttachmentDeletionService).should().deleteOne(42L, 11L);
    }

    @Test
    void 유효한_쿠키_Jwt로_CSRF_없이_첨부_다운로드_URL을_요청한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");

        mockMvc.perform(get("/attachments/11/download")
                        .cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isOk());

        then(tripAttachmentDownloadService).should().issueDownloadUrl(42L, 11L);
    }

    @Test
    void 유효한_쿠키_Jwt와_CSRF로_ZIP_다운로드_URL을_요청한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("attachment-bulk-download-browser"))
                .willReturn("csrf-token");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/attachments/bulk-download")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", "attachment-bulk-download-browser")
                        )
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .contentType("application/json")
                        .content("{\"tripAttachmentIds\":[11,12]}"))
                .andExpect(status().isOk());

        then(bulkAttachmentDownloadService).should()
                .issueDownloadUrl(42L, java.util.List.of(11L, 12L));
    }

    private byte[] jpeg() {
        return new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
    }

    private TripProcessingStatusResponse completed() {
        return new TripProcessingStatusResponse(
                7L,
                TripProcessingStatusResponse.Status.COMPLETED,
                new TripProcessingStatusResponse.Progress(1, 1),
                null,
                new TripProcessingStatusResponse.Result(7L, 1, 1, 0),
                null
        );
    }
}
