package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.service.TripAttachmentDetailService;
import com.yeodam.yeodambe.trip.service.TripAttachmentDeletionService;
import com.yeodam.yeodambe.trip.service.TripAttachmentDownloadService;
import com.yeodam.yeodambe.trip.service.BulkAttachmentDownloadService;
import com.yeodam.yeodambe.trip.service.TripAttachmentListService;
import com.yeodam.yeodambe.trip.service.TripAttachmentService;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import com.yeodam.yeodambe.user.security.SecurityConfig;
import com.yeodam.yeodambe.user.security.csrf.CsrfAccessDeniedHandler;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenGenerator;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.csrf.RdbCsrfTokenRepository;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
        RdbCsrfTokenRepository.class
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
    void 유효한_쿠키_Jwt와_CSRF로_첨부_서비스를_호출한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("attachment-browser")).willReturn("csrf-token");
        given(tripAttachmentService.uploadInitialAttachments(eq(7L), eq(42L), anyList()))
                .willReturn(completed());

        mockMvc.perform(multipart("/trips/7/initial-attachments")
                        .file("attachments[]", jpeg())
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", "attachment-browser")
                        )
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_PROCESSING_STATUS_FOUND"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        then(tripAttachmentService).should()
                .uploadInitialAttachments(eq(7L), eq(42L), anyList());
    }

    @Test
    void CSRF가_일치하지_않으면_403이고_첨부_서비스를_호출하지_않는다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("invalid-attachment-browser")).willReturn("csrf-token");

        mockMvc.perform(multipart("/trips/7/initial-attachments")
                        .file("attachments[]", jpeg())
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", "invalid-attachment-browser")
                        )
                        .header("X-CSRF-TOKEN", "wrong-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("CSRF_TOKEN_INVALID"));

        then(tripAttachmentService).should(never())
                .uploadInitialAttachments(any(), any(), anyList());
    }

    @Test
    void 인증_쿠키가_없으면_401이고_첨부_서비스를_호출하지_않는다() throws Exception {
        given(csrfTokenStore.find("unauthorized-attachment-browser")).willReturn("csrf-token");

        mockMvc.perform(multipart("/trips/7/initial-attachments")
                        .file("attachments[]", jpeg())
                        .cookie(new Cookie("CSRF_CONTEXT", "unauthorized-attachment-browser"))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));

        then(tripAttachmentService).should(never())
                .uploadInitialAttachments(any(), any(), anyList());
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
                ProcessingStatus.COMPLETED,
                new TripProcessingStatusResponse.Progress(1, 1),
                null,
                new TripProcessingStatusResponse.Result(7L, 1, 1, 0),
                null
        );
    }
}
